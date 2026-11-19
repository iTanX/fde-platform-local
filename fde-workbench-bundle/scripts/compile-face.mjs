import { createRequire } from 'node:module'
import { join, sep } from 'node:path'

const [face] = process.argv.slice(2)
const dshSrc = process.env.DSH_SRC
const ts = createRequire(join(dshSrc, 'package.json'))('typescript')
const cwd = process.cwd()
const configFile = join(cwd, `tsconfig.${face}.json`)
const config = ts.readConfigFile(configFile, ts.sys.readFile)
const dshConfig = join(dshSrc, face === 'host' ? 'tsconfig.base.json' : 'tsconfig.base.client.json')

function reportErrors(diagnostics) {
  if (diagnostics.length === 0) return
  console.error(ts.formatDiagnosticsWithColorAndContext(diagnostics, {
    getCanonicalFileName: file => file,
    getCurrentDirectory: () => cwd,
    getNewLine: () => '\n',
  }))
  process.exit(1)
}

if (config.error) reportErrors([config.error])
const parsed = ts.parseJsonConfigFileContent({
  ...config.config,
  extends: dshConfig,
}, ts.sys, cwd, {
  typeRoots: [join(dshSrc, 'node_modules', '@types')],
  declaration: false,
  declarationMap: false,
  sourceMap: false,
  composite: false,
  incremental: false,
}, configFile)
reportErrors(parsed.errors)

const program = ts.createProgram(parsed.fileNames, parsed.options)
const sourceDir = join(cwd, 'src') + sep
const files = program.getSourceFiles().filter(file => file.fileName.startsWith(sourceDir))
// DSH 源码仅参与类型解析。TS6059 是依赖源码位于本包 rootDir 外的诊断。
reportErrors([
  ...program.getOptionsDiagnostics(),
  ...program.getGlobalDiagnostics(),
  ...files.flatMap(file => [...program.getSyntacticDiagnostics(file), ...program.getSemanticDiagnostics(file)]),
].filter(diagnostic => diagnostic.code !== 6059))
// 逐个发射本包文件，避免把依赖的 JS 写回 pinned checkout。
for (const file of files) reportErrors(program.emit(file).diagnostics)
