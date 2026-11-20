// Local stand-in based on Slack's incoming webhook and rate-limit documentation.
// /_state and named synthetic service paths exist only to observe these tests.
const http = require("http");

http.createServer(async (req, res) => {
  const key = "slack-usage:counts";
  const counts = (await pm.state.get(key)) || { ok: 0, rate: 0, invalid: 0 };
  const path = req.url.split("?")[0];
  if (req.method === "GET" && path === "/_state") {
    res.writeHead(200, { "Content-Type": "application/json" });
    return res.end(JSON.stringify(counts));
  }
  if (req.method !== "POST" || !["/services/ok", "/services/rate"].includes(path)) {
    res.writeHead(501);
    return res.end("Unmodeled operation");
  }
  let body = req.body;
  if (typeof body === "string") {
    try { body = JSON.parse(body); } catch (_) { body = null; }
  }
  if (!(req.headers["content-type"] || "").startsWith("application/json")
      || !body || typeof body.text !== "string" || !body.text) {
    counts.invalid++;
    await pm.state.set(key, counts);
    res.writeHead(400);
    return res.end("invalid_payload");
  }
  const operation = path.endsWith("/rate") ? "rate" : "ok";
  counts[operation]++;
  await pm.state.set(key, counts);
  if (operation === "rate" && counts.rate === 1) {
    res.writeHead(429, { "Retry-After": "1" });
    return res.end("rate_limited");
  }
  res.writeHead(200, { "Content-Type": "text/plain" });
  res.end("ok");
}).listen(process.env.PORT || 4500);
