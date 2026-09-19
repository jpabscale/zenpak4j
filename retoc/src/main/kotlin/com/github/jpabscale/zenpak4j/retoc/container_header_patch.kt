// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
package com.github.jpabscale.zenpak4j.retoc

import java.io.ByteArrayInputStream

/**
 * Replace one package's `FPackageStoreEntry` inside an extracted container-header chunk.
 *
 * A packer that copies store entries verbatim (retoc's `pack-raw`, which reads them back out of
 * whatever ContainerHeader chunk it is handed) cannot pick up a payload-preserving repack on its
 * own: the entry's `export_bundles_size` still describes the cooker's chunk. Patching the header
 * chunk first makes the copied entry describe the repacked chunk.
 */
fun patch_container_header_store_entry(
    header_chunk: ByteArray,
    package_id: FPackageId,
    store_entry: StoreEntry,
): ByteArray {
    val header = FIoContainerHeader.deserialize(ByteArrayInputStream(header_chunk), null)
    header.add_package(package_id, store_entry)
    val out = SeekableByteArrayOutputStream()
    header.serialize(out)
    return out.toByteArray()
}
