// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
package com.github.jpabscale.zenpak4j.retoc

import java.io.ByteArrayInputStream

/** Bytes of an ExportBundleData chunk id that carry the package id (little-endian). */
private const val PACKAGE_ID_BYTES = 8

/** Hex characters per byte. */
private const val HEX_PAIR = 2

/** Radix of the chunk id's hexadecimal digits. */
private const val HEX_RADIX = 16

/** The `FPackageId` of an ExportBundleData chunk id (its first 8 bytes, little-endian). */
fun package_id_of_chunk_id(chunkIdHex: String): ULong {
    require(chunkIdHex.length >= PACKAGE_ID_BYTES * HEX_PAIR) {
        "chunk id '$chunkIdHex' is too short for a package id"
    }
    var value = 0uL
    for (i in 0 until PACKAGE_ID_BYTES) {
        val byte = chunkIdHex.substring(i * HEX_PAIR, i * HEX_PAIR + HEX_PAIR).toUInt(HEX_RADIX)
        value = value or (byte.toULong() shl (8 * i))
    }
    return value
}

/** The store entry for [packageId] inside a container header chunk's bytes, or null. */
fun store_entry_of_container_header(headerBytes: ByteArray, packageId: ULong): StoreEntry? =
    FIoContainerHeader.deserialize(ByteArrayInputStream(headerBytes), null)
        .get_store_entry(FPackageId(packageId))
