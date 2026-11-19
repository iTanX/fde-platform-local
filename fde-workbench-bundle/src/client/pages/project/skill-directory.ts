const ZIP32_MAX = 0xffffffff
const UTF8_NAMES = 0x0800

const crcTable = Uint32Array.from({ length: 256 }, (_, value) => {
  for (let bit = 0; bit < 8; bit += 1) {
    value = (value >>> 1) ^ ((value & 1) === 1 ? 0xedb88320 : 0)
  }
  return value
})

/** 目录上传沿用 ZIP 接口。用 ZIP 的存储模式封装文件，无需额外构建依赖。 */
export async function zipSkillDirectory(files: readonly File[]): Promise<File> {
  const folder = files[0]?.webkitRelativePath.split('/')[0]
  if (!folder || files.length > 0xffff) throw new Error('Invalid ZIP directory')

  const encoder = new TextEncoder()
  const entries: BlobPart[] = []
  const directory: BlobPart[] = []
  let offset = 0
  let directorySize = 0
  for (const file of files) {
    // 浏览器路径含所选目录名，服务端的 ZIP 根应对应所选目录内的内容。
    const path = file.webkitRelativePath.slice(folder.length + 1)
    const name = encoder.encode(path)
    if (!path || name.length > 0xffff || file.size + offset + 30 + name.length > ZIP32_MAX) {
      throw new Error('Invalid ZIP entry')
    }

    let crc = 0xffffffff
    const reader = file.stream().getReader()
    for (;;) {
      const chunk = await reader.read()
      if (chunk.done) break
      for (const byte of chunk.value) crc = (crc >>> 8) ^ crcTable[(crc ^ byte) & 0xff]!
    }
    crc ^= 0xffffffff

    const local = new DataView(new ArrayBuffer(30))
    local.setUint32(0, 0x04034b50, true)
    local.setUint16(4, 20, true)
    local.setUint16(6, UTF8_NAMES, true)
    local.setUint16(12, 0x21, true) // ZIP 日期以 1980-01-01 为起点
    local.setUint32(14, crc, true)
    local.setUint32(18, file.size, true)
    local.setUint32(22, file.size, true)
    local.setUint16(26, name.length, true)
    entries.push(local.buffer, name, file)

    const central = new DataView(new ArrayBuffer(46))
    central.setUint32(0, 0x02014b50, true)
    central.setUint16(4, 20, true)
    new Uint8Array(central.buffer).set(new Uint8Array(local.buffer, 4, 24), 6)
    central.setUint32(42, offset, true)
    directory.push(central.buffer, name)
    directorySize += central.byteLength + name.length
    offset += local.byteLength + name.length + file.size
  }

  const end = new DataView(new ArrayBuffer(22))
  if (offset + directorySize + end.byteLength > ZIP32_MAX) throw new Error('Invalid ZIP size')
  end.setUint32(0, 0x06054b50, true)
  end.setUint16(8, files.length, true)
  end.setUint16(10, files.length, true)
  end.setUint32(12, directorySize, true)
  end.setUint32(16, offset, true)
  return new File([...entries, ...directory, end.buffer], `${folder}.zip`, { type: 'application/zip' })
}
