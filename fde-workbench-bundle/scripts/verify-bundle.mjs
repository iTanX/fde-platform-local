#!/usr/bin/env node
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { Script } from 'node:vm'

const [libDir = fileURLToPath(new URL('../lib/', import.meta.url)), ...faces] = process.argv.slice(2)

if (faces.length === 0 || faces.includes('host')) {
  const source = readFileSync(join(libDir, 'index.js'), 'utf8')
  assert(source.trim(), 'Host 入口为空')
  // 安装步骤会在运行时依赖就位后 import 真实 Host 入口。
  execFileSync(process.execPath, ['--input-type=module', '--check'], {
    input: source,
    stdio: ['pipe', 'inherit', 'inherit'],
  })
  console.log('Host ESM 语法校验通过')
}

if (faces.length === 0 || faces.includes('client')) {
  const clientEntry = join(libDir, 'client.js')
  const source = readFileSync(clientEntry, 'utf8')
  new Script(source, { filename: clientEntry })
  // Rolldown 会压缩官方加载器前导，字符串引号及单个箭头参数的括号可以变化。
  assert(/^\s*window\.__ModuleLoader__\.load\(\s*\{\s*id:\s*(["'`])@local\/dsh-workbench-bundle\1\s*,\s*factory:\s*(?:\(\s*require\s*\)|require)\s*=>\s*\{/.test(source),
    'Client 入口缺少 DSH 模块工厂')
  const allowed = new Set(['react', 'react/jsx-runtime'])
  for (const [, , specifier] of source.matchAll(/\brequire\(\s*(["'`])([^"'`]+)\1\s*\)/g)) {
    assert(allowed.has(specifier), `Client 工厂存在意外的 require 依赖：${specifier}`)
  }
  console.log('Client 模块工厂和外部依赖校验通过')
}
