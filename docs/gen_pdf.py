"""Generate userguide.pdf from userguide.html via Chrome CDP (no header/footer)."""
import base64, json, subprocess, time, urllib.request
import websocket

CHROME  = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
HTML    = r"C:\Users\alanu\AndroidStudioProjects\ZLDREventReporter\docs\userguide.html"
PDF_OUT = r"C:\Users\alanu\AndroidStudioProjects\ZLDREventReporter\docs\userguide.pdf"
PORT    = 9222

# Launch Chrome with remote debugging
proc = subprocess.Popen([
    CHROME,
    f"--remote-debugging-port={PORT}",
    "--headless=old",
    "--disable-gpu",
    "--no-first-run",
    "--no-default-browser-check",
    "--remote-allow-origins=*",
    "about:blank",
])
time.sleep(2)

try:
    # Get first available tab from Chrome
    tabs = json.loads(urllib.request.urlopen(f"http://localhost:{PORT}/json").read())
    info = next(t for t in tabs if t.get("type") == "page")
    ws_url = info["webSocketDebuggerUrl"]

    ws = websocket.create_connection(ws_url, timeout=30)
    cmd_id = [0]

    def send(method, params=None):
        cmd_id[0] += 1
        ws.send(json.dumps({"id": cmd_id[0], "method": method, "params": params or {}}))
        return cmd_id[0]

    def wait_for(event_or_id, timeout=20):
        deadline = time.time() + timeout
        while time.time() < deadline:
            msg = json.loads(ws.recv())
            if "method" in msg and msg["method"] == event_or_id:
                return msg
            if "id" in msg and msg["id"] == event_or_id:
                return msg
        raise TimeoutError(f"Timed out waiting for {event_or_id}")

    # Navigate to the HTML file
    send("Page.enable")
    nav_id = send("Page.navigate", {"url": f"file:///{HTML.replace(chr(92), '/')}"})
    wait_for("Page.loadEventFired", timeout=15)
    time.sleep(1)  # let images render

    # Print to PDF — no header/footer
    pdf_id = send("Page.printToPDF", {
        "displayHeaderFooter": False,
        "printBackground": True,
        "preferCSSPageSize": True,
        "paperWidth": 8.27,   # A4 inches
        "paperHeight": 11.69,
        "marginTop": 0,
        "marginBottom": 0,
        "marginLeft": 0,
        "marginRight": 0,
    })
    result = wait_for(pdf_id, timeout=30)
    pdf_data = base64.b64decode(result["result"]["data"])

    with open(PDF_OUT, "wb") as f:
        f.write(pdf_data)

    print(f"PDF written: {len(pdf_data):,} bytes → {PDF_OUT}")
    ws.close()

finally:
    proc.terminate()
