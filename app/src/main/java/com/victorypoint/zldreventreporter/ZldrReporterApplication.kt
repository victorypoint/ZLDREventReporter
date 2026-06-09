package com.victorypoint.zldreventreporter

import android.app.Application
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.victorypoint.zldreventreporter.data.SyncMetadataStore
import com.victorypoint.zldreventreporter.data.auth.AuthApi
import com.victorypoint.zldreventreporter.data.auth.AuthInterceptor
import com.victorypoint.zldreventreporter.data.auth.AuthRepository
import com.victorypoint.zldreventreporter.data.auth.TokenStore
import com.victorypoint.zldreventreporter.data.db.EventStatsRepository
import com.victorypoint.zldreventreporter.data.db.ZldrReporterDatabase
import com.victorypoint.zldreventreporter.data.events.EventSyncRepository
import com.victorypoint.zldreventreporter.data.events.EventsApi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class ZldrReporterApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        schedulePeriodicSync()
    }

    private fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }


    val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    val tokenStore: TokenStore by lazy { TokenStore(this) }

    val authApi: AuthApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://secure.zwift.com/")
            .client(baseOkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(AuthApi::class.java)
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(authApi, tokenStore)
    }

    val eventsApi: EventsApi by lazy {
        val authInterceptor = AuthInterceptor(tokenStore, authRepository)
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("User-Agent", "Zwift/115 CFNetwork/758.0.2 Darwin/15.0.0")
                    .build()
                chain.proceed(request)
            }
            .build()

        Retrofit.Builder()
            .baseUrl("https://us-or-rly101.zwift.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(EventsApi::class.java)
    }

    val database: ZldrReporterDatabase by lazy {
        Room.databaseBuilder(this, ZldrReporterDatabase::class.java, "zldr_reporter_db")
            .addMigrations(ZldrReporterDatabase.MIGRATION_1_2, ZldrReporterDatabase.MIGRATION_2_3)
            .build()
    }

    val eventStatsRepository: EventStatsRepository by lazy {
        EventStatsRepository(database.eventStatDao())
    }

    val syncMetadataStore: SyncMetadataStore by lazy {
        SyncMetadataStore(this)
    }

    val eventSyncRepository: EventSyncRepository by lazy {
        EventSyncRepository(eventsApi, eventStatsRepository, syncMetadataStore)
    }

    private fun baseOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()
}
