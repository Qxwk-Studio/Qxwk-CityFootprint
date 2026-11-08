#!/usr/bin/env node
/**
 * 根据 release.json 生成 D1 app_versions 的更新 SQL。
 * 只写当前版本；version_code 冲突时覆盖。
 */
import { readFileSync, writeFileSync } from 'node:fs';

const release = JSON.parse(readFileSync('release.json', 'utf8'));
const versionName = String(release.versionName || '').trim();
const versionCode = Number(release.versionCode);
const importance = Number(release.importance ?? 0);
const repo = process.env.GITHUB_REPOSITORY || '';
const tag = `v${versionName}`;

if (!repo) {
  console.error('::error::缺少 GITHUB_REPOSITORY');
  process.exit(1);
}
if (!Number.isSafeInteger(versionCode) || versionCode <= 0) {
  console.error('::error::release.json 的 versionCode 不合法');
  process.exit(1);
}
if (![0, 1, 2].includes(importance)) {
  console.error('::error::release.json 的 importance 只能是 0 / 1 / 2');
  process.exit(1);
}

const notes = (Array.isArray(release.cfNotes) ? release.cfNotes : [])
  .map((line) => String(line).trim())
  .filter(Boolean)
  .join('\n');
if (!notes) {
  console.error('::error::release.json 的 cfNotes 不能为空');
  process.exit(1);
}

const downloadUrl = `https://github.com/${repo}/releases/download/${tag}/CityFootprint-${versionName}.apk`;
const esc = (value) => String(value).replaceAll("'", "''");

const sql = `INSERT INTO app_versions (version_name, version_code, download_url, notes, importance)
VALUES ('${esc(versionName)}', ${versionCode}, '${esc(downloadUrl)}', '${esc(notes)}', ${importance})
ON CONFLICT(version_code) DO UPDATE SET
  version_name = excluded.version_name,
  download_url = excluded.download_url,
  notes = excluded.notes,
  importance = excluded.importance;
`;

writeFileSync('backend/.release-version.sql', sql, 'utf8');
console.log(`Wrote backend/.release-version.sql for ${versionName} (${versionCode})`);
