#!/usr/bin/env node
/**
 * 从根目录 CHANGELOG.md 读取最新版本和该版本的更新日志。
 * 版本号格式：## 2.2.0 - 2026-11-08
 * versionCode 由语义版本推导：major * 10000 + minor * 100 + patch。
 *
 * 输出到 GITHUB_OUTPUT，并写 release-notes.md 供 gh release 使用。
 */
import { readFileSync, writeFileSync, appendFileSync } from 'node:fs';

const changelogPath = 'CHANGELOG.md';
const notesPath = 'release-notes.md';

function fail(message) {
  console.error(`::error::${message}`);
  process.exit(1);
}

const text = readFileSync(changelogPath, 'utf8');
const heading = /^##[ \t]+\[?v?([0-9]+\.[0-9]+\.[0-9]+)\]?/m.exec(text);
if (!heading) fail('CHANGELOG.md 没有找到最新版本标题，例如：## 2.2.0 - 2026-11-08');

const versionName = heading[1];
const [major, minor, patch] = versionName.split('.').map(Number);
const versionCode = major * 10000 + minor * 100 + patch;
if (!Number.isSafeInteger(versionCode) || versionCode <= 0) fail(`无法从 ${versionName} 推导 versionCode`);

const bodyStart = text.indexOf('\n', heading.index) + 1;
const nextHeading = /^##[ \t]+/m.exec(text.slice(bodyStart));
const bodyEnd = nextHeading ? bodyStart + nextHeading.index : text.length;
const body = text.slice(bodyStart, bodyEnd).trim();
if (!body) fail(`${versionName} 的更新日志为空`);

const tag = `v${versionName}`;
writeFileSync(notesPath, body + '\n', 'utf8');

const output = process.env.GITHUB_OUTPUT;
if (output) {
  appendFileSync(output, [
    `version_name=${versionName}`,
    `version_code=${versionCode}`,
    `tag=${tag}`,
    '',
  ].join('\n'), 'utf8');
}

console.log(`version_name=${versionName}`);
console.log(`version_code=${versionCode}`);
console.log(`tag=${tag}`);
console.log(`release notes: ${notesPath}`);
