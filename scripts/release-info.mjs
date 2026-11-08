#!/usr/bin/env node
/**
 * 从根目录 release.json 读取当前版本的版本号和更新日志。
 * release.json 只保存「当前要发布」的版本，历史日志由 GitHub Release 自己保存。
 *
 * 输出到 GITHUB_OUTPUT，并写 release-notes.md 供 gh release 使用。
 */
import { readFileSync, writeFileSync, appendFileSync } from 'node:fs';

const releasePath = 'release.json';
const notesPath = 'release-notes.md';

function fail(message) {
  console.error(`::error::${message}`);
  process.exit(1);
}

let release;
try {
  release = JSON.parse(readFileSync(releasePath, 'utf8'));
} catch (error) {
  fail(`无法读取或解析 ${releasePath}: ${error.message}`);
}

const versionName = String(release.versionName || '').trim();
if (!/^[0-9]+\.[0-9]+\.[0-9]+$/.test(versionName)) {
  fail(`versionName 必须是 x.y.z，收到：${versionName || '(空)'}`);
}

const versionCode = Number(release.versionCode);
if (!Number.isSafeInteger(versionCode) || versionCode <= 0) {
  fail(`versionCode 必须是正整数，收到：${release.versionCode}`);
}
const importance = Number(release.importance ?? 0);
if (![0, 1, 2].includes(importance)) {
  fail('importance 只能是 0 / 1 / 2');
}

const lines = Array.isArray(release.changelog) ? release.changelog : [];
if (lines.length === 0 || lines.some((line) => typeof line !== 'string')) {
  fail('changelog 必须是非空字符串数组');
}
const body = lines.join('\n').trim();
if (!body) fail('changelog 不能为空');

const cfNotes = Array.isArray(release.cfNotes) ? release.cfNotes : [];
if (cfNotes.length === 0 || cfNotes.some((line) => typeof line !== 'string' || !line.trim())) {
  fail('cfNotes 必须是非空字符串数组，用于 App 更新提示');
}

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
