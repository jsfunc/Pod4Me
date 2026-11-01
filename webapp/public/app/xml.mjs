// Shared by the browser and relay. Inspect declarations before parsing entities.
export function decodeXml(input, contentType = '', partial = false) {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
  let encoding;
  if (bytes[0] === 0xff && bytes[1] === 0xfe) encoding = 'utf-16le';
  else if (bytes[0] === 0xfe && bytes[1] === 0xff) encoding = 'utf-16be';
  else if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) encoding = 'utf-8';
  else if (bytes[0] === 0 && bytes[1] === 60) encoding = 'utf-16be';
  else if (bytes[0] === 60 && bytes[1] === 0) encoding = 'utf-16le';
  else encoding = /charset\s*=\s*["']?([^;\s"']+)/i.exec(contentType)?.[1] ||
    /^\s*<\?xml\s[^?]*encoding\s*=\s*["']([^"']+)/i.exec(new TextDecoder().decode(bytes.subarray(0, 512)))?.[1] || 'utf-8';
  try { return new TextDecoder(encoding, {fatal: true}).decode(bytes, {stream: partial}); }
  catch { throw new Error('The feed uses an unsupported or invalid text encoding.'); }
}

export function xmlPreamble(text) {
  let prefix = text.slice(0, 65536).replace(/^\uFEFF/, '').trimStart();
  for (let i = 0; i < 256; i++) {
    const end = prefix.startsWith('<?') ? '?>' : prefix.startsWith('<!--') ? '-->' : '';
    if (!end) break;
    const at = prefix.indexOf(end);
    if (at < 0) throw new Error('The XML preamble is incomplete or too long.');
    prefix = prefix.slice(at + end.length).trimStart();
  }
  if (/^<!DOCTYPE\b/i.test(prefix)) throw new Error('XML document types are not supported.');
  if (!/^<[A-Za-z_][\w.:-]*(?:\s|\/?>)/.test(prefix)) throw new Error('The XML preamble is invalid or too long.');
  return prefix;
}
