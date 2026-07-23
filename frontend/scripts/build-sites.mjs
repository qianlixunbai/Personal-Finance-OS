import assert from 'node:assert/strict';
import { readFile, rm, mkdir, writeFile, readdir } from 'node:fs/promises';
import { dirname, resolve, relative, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const frontendDirectory = resolve(scriptDirectory, '..');
const repositoryDirectory = resolve(frontendDirectory, '..');
const viteDistDirectory = resolve(frontendDirectory, 'dist');
const hostingConfigPath = resolve(repositoryDirectory, '.openai', 'hosting.json');

function isWithinDirectory(candidate, directory) {
  const pathFromDirectory = relative(directory, candidate);
  return pathFromDirectory && !pathFromDirectory.startsWith(`..${sep}`) && pathFromDirectory !== '..';
}

function localAssetPath(assetReference) {
  if (/^(?:[a-z][a-z\d+.-]*:|\/\/|#)/i.test(assetReference)) {
    throw new Error(`Sites build only supports local Vite assets: ${assetReference}`);
  }

  const assetPath = resolve(viteDistDirectory, assetReference.split(/[?#]/, 1)[0]);
  if (!isWithinDirectory(assetPath, viteDistDirectory)) {
    throw new Error(`Asset path escapes Vite dist: ${assetReference}`);
  }
  return assetPath;
}

function escapeInlineScript(source) {
  return source.replace(/<\/script/gi, '<\\/script');
}

function htmlString(value) {
  return JSON.stringify(value).replace(/<\/script/gi, '<\\/script');
}

async function readLocalText(assetReference) {
  return readFile(localAssetPath(assetReference), 'utf8');
}

async function inlineBuildAssets(indexHtml) {
  let html = indexHtml;

  const scriptMatches = [...html.matchAll(/<script\b(?=[^>]*\bsrc=["'][^"']+["'])[^>]*\bsrc=["']([^"']+)["'][^>]*>\s*<\/script>/gi)];
  for (const match of scriptMatches) {
    const source = await readLocalText(match[1]);
    const attributes = match[0]
      .replace(/\bsrc=["'][^"']+["']/i, '')
      .replace(/<script\b|>\s*<\/script>/gi, '')
      .trim();
    const inlineTag = `<script${attributes ? ` ${attributes}` : ''}>${escapeInlineScript(source)}</script>`;
    html = html.replace(match[0], () => inlineTag);
  }

  const stylesheetMatches = [...html.matchAll(/<link\b(?=[^>]*\brel=["']stylesheet["'])[^>]*\bhref=["']([^"']+)["'][^>]*>/gi)];
  for (const match of stylesheetMatches) {
    const stylesheet = await readLocalText(match[1]);
    html = html.replace(match[0], () => `<style>${stylesheet}</style>`);
  }

  const inaccessibleAssetTag = html.match(/<script\b[^>]*\bsrc=["'](?:\.?\/)?assets\//i) ?? html.match(/<link\b(?=[^>]*\brel=["']stylesheet["'])[^>]*\bhref=["'](?:\.?\/)?assets\//i);
  if (inaccessibleAssetTag) {
    throw new Error(`Inlined HTML still references an inaccessible Vite /assets/ tag: ${inaccessibleAssetTag[0]}`);
  }

  if (!html.includes('<div id="root"></div>')) {
    throw new Error('Vite HTML does not contain the React root node');
  }
  if (!html.includes('<script') || !html.includes('<style>')) {
    throw new Error('Vite JavaScript and CSS were not both inlined');
  }

  return html;
}

async function readRootStaticAssets() {
  const assets = {};
  for (const fileName of ['favicon.svg', 'icons.svg']) {
    const sourcePath = resolve(viteDistDirectory, fileName);
    try {
      assets[`/${fileName}`] = {
        body: await readFile(sourcePath, 'utf8'),
        contentType: 'image/svg+xml',
      };
    } catch (error) {
      if (error?.code !== 'ENOENT') throw error;
    }
  }
  return assets;
}

function workerSource(html, staticAssets) {
  return `const HTML = ${htmlString(html)};
const STATIC_ASSETS = ${JSON.stringify(staticAssets)};

function responseFor(method, body, contentType, status = 200, extraHeaders = {}) {
  return new Response(method === 'HEAD' ? null : body, {
    status,
    headers: {
      'content-type': contentType,
      ...extraHeaders,
    },
  });
}

export default {
  async fetch(request, env = {}) {
    const { method } = request;
    if (method !== 'GET' && method !== 'HEAD') {
      return responseFor(method, 'Method Not Allowed', 'text/plain; charset=utf-8', 405, {
        allow: 'GET, HEAD',
      });
    }

    const { pathname } = new URL(request.url);
    if (pathname === '/' || pathname === '/index.html') {
      return responseFor(method, HTML, 'text/html; charset=utf-8');
    }

    const staticAsset = STATIC_ASSETS[pathname];
    if (staticAsset) {
      return responseFor(method, staticAsset.body, staticAsset.contentType);
    }

    return responseFor(method, 'Not Found', 'text/plain; charset=utf-8', 404);
  },
};
`;
}

async function emptyDirectory(directory) {
  await rm(directory, { recursive: true, force: true });
  await mkdir(directory, { recursive: true });
}

async function verifyWorker(workerPath) {
  const workerModule = await import(pathToFileURL(workerPath).href);
  assert.equal(typeof workerModule.default?.fetch, 'function', 'Worker must export default.fetch');

  const getResponse = await workerModule.default.fetch(new Request('https://example.test/'));
  assert.equal(getResponse.status, 200, 'GET / must return 200');
  assert.equal(getResponse.headers.get('content-type'), 'text/html; charset=utf-8');
  const getHtml = await getResponse.text();
  assert.match(getHtml, /<div id="root"><\/div>/, 'GET / must contain the React root');
  assert.match(getHtml, /<script(?:\s[^>]*)?>/, 'GET / must contain inline JavaScript');
  assert.match(getHtml, /<style>/, 'GET / must contain inline CSS');
  assert.doesNotMatch(getHtml, /<script\b[^>]*\bsrc=["'](?:\.?\/)?assets\//i, 'GET / must not reference Vite script assets');
  assert.doesNotMatch(getHtml, /<link\b(?=[^>]*\brel=["']stylesheet["'])[^>]*\bhref=["'](?:\.?\/)?assets\//i, 'GET / must not reference Vite stylesheet assets');

  const headResponse = await workerModule.default.fetch(new Request('https://example.test/', { method: 'HEAD' }));
  assert.equal(headResponse.status, 200, 'HEAD / must return 200');
  assert.equal(headResponse.headers.get('content-type'), 'text/html; charset=utf-8');
  assert.equal(await headResponse.text(), '', 'HEAD / must not return a body');

  const postResponse = await workerModule.default.fetch(new Request('https://example.test/', { method: 'POST' }));
  assert.equal(postResponse.status, 405, 'POST / must return 405');
  assert.equal(postResponse.headers.get('allow'), 'GET, HEAD');

  const notFoundResponse = await workerModule.default.fetch(new Request('https://example.test/missing'));
  assert.equal(notFoundResponse.status, 404, 'Unknown paths must return 404');
}

async function main() {
  const [indexHtml, hostingConfig] = await Promise.all([
    readFile(resolve(viteDistDirectory, 'index.html'), 'utf8'),
    readFile(hostingConfigPath, 'utf8'),
  ]);
  JSON.parse(hostingConfig);

  const [inlinedHtml, staticAssets] = await Promise.all([
    inlineBuildAssets(indexHtml),
    readRootStaticAssets(),
  ]);
  const generatedWorker = workerSource(inlinedHtml, staticAssets);

  for (const forbiddenPattern of [/node:http/, /node:fs/, /server\.listen/]) {
    assert.doesNotMatch(generatedWorker, forbiddenPattern, `Worker contains forbidden runtime code: ${forbiddenPattern}`);
  }

  await emptyDirectory(viteDistDirectory);
  await Promise.all([
    mkdir(resolve(viteDistDirectory, 'server'), { recursive: true }),
    mkdir(resolve(viteDistDirectory, '.openai'), { recursive: true }),
    mkdir(resolve(viteDistDirectory, 'client'), { recursive: true }),
  ]);

  const workerPath = resolve(viteDistDirectory, 'server', 'index.js');
  await Promise.all([
    writeFile(workerPath, generatedWorker, 'utf8'),
    writeFile(resolve(viteDistDirectory, '.openai', 'hosting.json'), hostingConfig, 'utf8'),
    writeFile(resolve(viteDistDirectory, 'client', 'index.html'), inlinedHtml, 'utf8'),
  ]);

  await verifyWorker(workerPath);

  const topLevelEntries = (await readdir(viteDistDirectory)).sort();
  assert.deepEqual(topLevelEntries, ['.openai', 'client', 'server']);
  console.log('Built and verified Sites Worker package at frontend/dist');
}

await main();
