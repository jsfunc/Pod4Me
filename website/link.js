/* Shared with Node tests. No network requests or redirects on page load. */
(function (root) {
  "use strict";
  function httpUrl(value) {
    if (!value || /\s/.test(value)) throw new Error("The recommendation has an invalid address.");
    let url;
    try { url = new URL(value); } catch (_) { throw new Error("The recommendation has an invalid address."); }
    if (!/^https?:$/.test(url.protocol) || !url.hostname || url.username || url.password)
      throw new Error("The recommendation must use a public HTTP or HTTPS address without a password.");
    return value;
  }
  function parse(hash) {
    if (!hash || hash === "#") return null;
    if (hash.length > 64000) throw new Error("This recommendation is too long.");
    const match = /^#(episode|feed)\?([^#]+)$/.exec(hash);
    if (!match) throw new Error("This recommendation link is incomplete. Ask the sender to share it again from Pod4Me.");
    const fields = new Map();
    for (const field of match[2].split("&")) {
      const at = field.indexOf("=");
      if (at < 0) continue;
      let key, value;
      try {
        key = decodeURIComponent(field.slice(0, at).replace(/\+/g, " "));
        value = decodeURIComponent(field.slice(at + 1).replace(/\+/g, " "));
      } catch (_) { throw new Error("This recommendation link has damaged text. Ask the sender to share it again."); }
      if (fields.has(key)) throw new Error("This recommendation has duplicate fields.");
      fields.set(key, value);
    }
    return {kind:match[1], url:httpUrl(fields.get("url")), title:fields.get("title") || "",
      feed:fields.has("feed") ? httpUrl(fields.get("feed")) : null};
  }
  function intent(item) {
    let query = "url=" + encodeURIComponent(item.url) + "&title=" + encodeURIComponent(item.title);
    if (item.feed) query += "&feed=" + encodeURIComponent(item.feed);
    return "intent://" + item.kind + "?" + query + "#Intent;scheme=pod4me;package=com.pod4me;end";
  }
  const api = {parse, intent};
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.Pod4MeLinks = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
