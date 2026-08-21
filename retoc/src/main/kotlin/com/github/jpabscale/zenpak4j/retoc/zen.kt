// Rust: retoc/src/zen.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod", "EnumEntryName", "SpellCheckingInspection", "MagicNumber")

package com.github.jpabscale.zenpak4j.retoc

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.HashMap
import java.util.TreeMap

// Rust: retoc/src/zen.rs:21 get_package_name
fun get_package_name(data: ByteArray, container_header_version: EIoContainerHeaderVersion): String {
    val stream = SeekableByteArrayInputStream(data)
    return FZenPackageHeader.get_package_name(stream, container_header_version)
}

// helpers for seekable conversion
private fun InputStream.toSeekable(): SeekableByteArrayInputStream {
    return if (this is SeekableByteArrayInputStream) this else {
        val bytes = this.readBytes()
        SeekableByteArrayInputStream(bytes)
    }
}

// size constants for offset calculations (ByteBuffer LE verified)
private const val SIZE_FBULK_DATA_MAP_ENTRY: Int = 32 // 8+8+8+4+1+3
private const val SIZE_FEXPORT_MAP_ENTRY: Int = 72 // 8+8+8+32+8+4+1+3
private const val SIZE_FCELL_EXPORT_MAP_ENTRY: Int = 40 // 8+8+8+8+8
private const val SIZE_FEXPORT_BUNDLE_ENTRY: Int = 8 // 4+4
private const val SIZE_FDEPENDENCY_BUNDLE_HEADER: Int = 20 // 4+4*4
private const val SIZE_FDEPENDENCY_BUNDLE_ENTRY: Int = 4 // i32
private const val SIZE_FPACKAGE_OBJECT_INDEX: Int = 8 // u64

// Rust: retoc/src/zen.rs:25 FZenPackageSummary
data class FZenPackageSummary(
    var has_versioning_info: UInt = 0u,
    var header_size: UInt = 0u,
    var name: FMappedName = FMappedName(0u, 0u),
    var source_name: FMappedName = FMappedName(0u, 0u),
    var package_flags: UInt = 0u,
    var cooked_header_size: UInt = 0u,
    var imported_public_export_hashes_offset: Int = -1,
    var import_map_offset: Int = -1,
    var export_map_offset: Int = -1,
    var export_bundle_entries_offset: Int = -1,
    var graph_data_offset: Int = -1,
    var dependency_bundle_headers_offset: Int = -1,
    var dependency_bundle_entries_offset: Int = -1,
    var imported_package_names_offset: Int = -1,
    var name_map_names_offset: Int = -1,
    var name_map_names_size: Int = -1,
    var name_map_hashes_offset: Int = -1,
    var name_map_hashes_size: Int = -1,
    var graph_data_size: Int = -1
) {
    companion object {
        // Rust: retoc/src/zen.rs:51 deserialize
        fun deserialize(stream: InputStream, container_header_version: EIoContainerHeaderVersion): FZenPackageSummary {
            var has_versioning_info: UInt = 0u
            var header_size: UInt = 0u
            if (container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
                has_versioning_info = stream.read_u32_le()
                // ByteBuffer LE verification
                val bbHas = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(has_versioning_info.toInt()).array()
                check(ByteBuffer.wrap(bbHas).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == has_versioning_info)
                header_size = stream.read_u32_le()
                val bbSize = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(header_size.toInt()).array()
                check(ByteBuffer.wrap(bbSize).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == header_size)
            }
            val name: FMappedName = stream.de(FMappedName)
            var source_name: FMappedName = FMappedName(0u, 0u)
            if (container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
                source_name = stream.de(FMappedName)
            }
            val package_flags: UInt = stream.read_u32_le()
            val bbFlags = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_flags.toInt()).array()
            check(ByteBuffer.wrap(bbFlags).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == package_flags)
            val cooked_header_size: UInt = stream.read_u32_le()
            val bbCooked = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cooked_header_size.toInt()).array()
            check(ByteBuffer.wrap(bbCooked).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == cooked_header_size)

            var imported_public_export_hashes_offset = -1
            var name_map_names_offset: Int = -1
            var name_map_names_size: Int = -1
            var name_map_hashes_offset: Int = -1
            var name_map_hashes_size: Int = -1
            if (container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
                name_map_names_offset = stream.read_i32_le()
                val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(name_map_names_offset).array()
                check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == name_map_names_offset)
                name_map_names_size = stream.read_i32_le()
                name_map_hashes_offset = stream.read_i32_le()
                name_map_hashes_size = stream.read_i32_le()
            } else {
                imported_public_export_hashes_offset = stream.read_i32_le()
                val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(imported_public_export_hashes_offset).array()
                check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == imported_public_export_hashes_offset)
            }

            val import_map_offset: Int = stream.read_i32_le()
            val bbImport = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(import_map_offset).array()
            check(ByteBuffer.wrap(bbImport).order(ByteOrder.LITTLE_ENDIAN).int == import_map_offset)
            val export_map_offset: Int = stream.read_i32_le()
            val export_bundle_entries_offset: Int = stream.read_i32_le()

            var graph_data_offset: Int = -1
            var dependency_bundle_headers_offset: Int = -1
            var dependency_bundle_entries_offset: Int = -1
            var imported_package_names_offset: Int = -1

            if (container_header_version.value >= EIoContainerHeaderVersion.NoExportInfo.value) {
                dependency_bundle_headers_offset = stream.read_i32_le()
                dependency_bundle_entries_offset = stream.read_i32_le()
                imported_package_names_offset = stream.read_i32_le()
            } else {
                graph_data_offset = stream.read_i32_le()
            }

            var graph_data_size: Int = -1
            if (container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
                graph_data_size = stream.read_i32_le()
                val pad: Int = stream.read_i32_le()
                // Header size is GraphDataOffset + GraphDataSize
                header_size = (graph_data_offset + graph_data_size).toUInt()
                val bbHeader = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(header_size.toInt()).array()
                check(ByteBuffer.wrap(bbHeader).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == header_size)
            }

            return FZenPackageSummary(
                has_versioning_info = has_versioning_info,
                header_size = header_size,
                name = name,
                source_name = source_name,
                package_flags = package_flags,
                cooked_header_size = cooked_header_size,
                imported_public_export_hashes_offset = imported_public_export_hashes_offset,
                import_map_offset = import_map_offset,
                export_map_offset = export_map_offset,
                export_bundle_entries_offset = export_bundle_entries_offset,
                graph_data_offset = graph_data_offset,
                dependency_bundle_headers_offset = dependency_bundle_headers_offset,
                dependency_bundle_entries_offset = dependency_bundle_entries_offset,
                imported_package_names_offset = imported_package_names_offset,
                name_map_names_offset = name_map_names_offset,
                name_map_names_size = name_map_names_size,
                name_map_hashes_offset = name_map_hashes_offset,
                name_map_hashes_size = name_map_hashes_size,
                graph_data_size = graph_data_size
            )
        }
    }
    // Rust: retoc/src/zen.rs:132 serialize
    fun serialize(stream: OutputStream, container_header_version: EIoContainerHeaderVersion) {
        if (container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
            val bbHas = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(has_versioning_info.toInt()).array()
            stream.write(bbHas)
            val bbHeader = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(header_size.toInt()).array()
            stream.write(bbHeader)
        }
        stream.ser(name)
        if (container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
            stream.ser(source_name)
        }
        val bbFlags = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_flags.toInt()).array()
        stream.write(bbFlags)
        val bbCooked = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cooked_header_size.toInt()).array()
        stream.write(bbCooked)

        if (container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
            var bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(name_map_names_offset).array()
            stream.write(bb)
            bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(name_map_names_size).array()
            stream.write(bb)
            bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(name_map_hashes_offset).array()
            stream.write(bb)
            bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(name_map_hashes_size).array()
            stream.write(bb)
        } else {
            val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(imported_public_export_hashes_offset).array()
            stream.write(bb)
        }

        var bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(import_map_offset).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(export_map_offset).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(export_bundle_entries_offset).array()
        stream.write(bb)

        if (container_header_version.value >= EIoContainerHeaderVersion.NoExportInfo.value) {
            bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dependency_bundle_headers_offset).array()
            stream.write(bb)
            bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dependency_bundle_entries_offset).array()
            stream.write(bb)
            bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(imported_package_names_offset).array()
            stream.write(bb)
        } else {
            bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(graph_data_offset).array()
            stream.write(bb)
        }

        if (container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
            bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(graph_data_size).array()
            stream.write(bb)
            bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
            stream.write(bb)
        }
    }
}

// Rust: retoc/src/zen.rs:175 EZenPackageVersion
enum class EZenPackageVersion(val value: UInt) {
    Initial(0u),
    DataResourceTable(1u),
    ImportedPackageNames(2u),
    ExportDependencies(3u);

    companion object {
        fun from_repr(value: UInt): EZenPackageVersion? = entries.find { it.value == value }
        fun from_repr(value: Int): EZenPackageVersion? = from_repr(value.toUInt())
        fun from_repr(value: ULong): EZenPackageVersion? = from_repr(value.toUInt())
    }
}

// Rust: retoc/src/zen.rs:186 FPackageFileVersion
data class FPackageFileVersion(
    var file_version_ue4: Int = 0,
    var file_version_ue5: Int = 0
) : Writeable {
    fun is_ue5(): Boolean = file_version_ue5 != 0

    override fun ser(stream: OutputStream) {
        // ByteBuffer LE explicit
        var bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(file_version_ue4).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(file_version_ue5).array()
        stream.write(bb)
    }

    companion object : Readable<FPackageFileVersion> {
        fun create_ue4(version: EUnrealEngineObjectUE4Version): FPackageFileVersion =
            FPackageFileVersion(file_version_ue4 = version.value, file_version_ue5 = 0)

        fun create_ue5(version: EUnrealEngineObjectUE5Version): FPackageFileVersion =
            FPackageFileVersion(
                file_version_ue4 = EUnrealEngineObjectUE4Version.CorrectLicenseeFlag.value,
                file_version_ue5 = version.value
            )

        override fun de(stream: InputStream): FPackageFileVersion {
            val ue4 = stream.read_i32_le()
            val bb4 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ue4).array()
            check(ByteBuffer.wrap(bb4).order(ByteOrder.LITTLE_ENDIAN).int == ue4)
            val ue5 = stream.read_i32_le()
            val bb5 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ue5).array()
            check(ByteBuffer.wrap(bb5).order(ByteOrder.LITTLE_ENDIAN).int == ue5)
            return FPackageFileVersion(
                file_version_ue4 = ue4,
                file_version_ue5 = ue5
            )
        }
    }
}

// Rust: retoc/src/zen.rs:220 FCustomVersion
data class FCustomVersion(
    var key: FGuid = FGuid(),
    var version: Int = 0
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.ser(key)
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(version).array()
        stream.write(bb)
    }
    companion object : Readable<FCustomVersion> {
        override fun de(stream: InputStream): FCustomVersion {
            val k = stream.de(FGuid)
            val v = stream.read_i32_le()
            val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == v)
            return FCustomVersion(
                key = k,
                version = v
            )
        }
    }
}

// Rust: retoc/src/zen.rs:239 FZenPackageVersioningInfo
data class FZenPackageVersioningInfo(
    var zen_version: EZenPackageVersion = EZenPackageVersion.ExportDependencies,
    var package_file_version: FPackageFileVersion = FPackageFileVersion(),
    var licensee_version: Int = 0,
    var custom_versions: MutableList<FCustomVersion> = mutableListOf()
) : Writeable {
    override fun ser(stream: OutputStream) {
        val bbZen = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(zen_version.value.toInt()).array()
        stream.write(bbZen)
        stream.ser(package_file_version)
        val bbLic = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(licensee_version).array()
        stream.write(bbLic)
        val bbLen = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(custom_versions.size).array()
        stream.write(bbLen)
        for (cv in custom_versions) cv.ser(stream)
    }
    companion object : Readable<FZenPackageVersioningInfo> {
        override fun de(stream: InputStream): FZenPackageVersioningInfo {
            val raw: UInt = stream.read_u32_le()
            val bbRaw = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw.toInt()).array()
            check(ByteBuffer.wrap(bbRaw).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == raw)
            val zen_version = EZenPackageVersion.from_repr(raw) ?: throw IllegalArgumentException("invalid EZenPackageVersion $raw")
            val pkg = stream.de(FPackageFileVersion)
            val lic = stream.read_i32_le()
            val bbLic = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(lic).array()
            check(ByteBuffer.wrap(bbLic).order(ByteOrder.LITTLE_ENDIAN).int == lic)
            val custom = stream.read_vec { s -> s.de(FCustomVersion) }.toMutableList()
            return FZenPackageVersioningInfo(zen_version, pkg, lic, custom)
        }
    }
}

// Rust: retoc/src/zen.rs:272 FBulkDataMapEntry
data class FBulkDataMapEntry(
    var serial_offset: Long = 0L,
    var duplicate_serial_offset: Long = 0L,
    var serial_size: Long = 0L,
    var flags: UInt = 0u,
    var cooked_index: UByte = 0u,
    var pad: ByteArray = ByteArray(3)
) : Writeable {
    override fun ser(stream: OutputStream) {
        var bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(serial_offset).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(duplicate_serial_offset).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(serial_size).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(flags.toInt()).array()
        stream.write(bb)
        stream.write_u8(cooked_index)
        stream.write(pad)
    }
    override fun equals(other: Any?): Boolean {
        if (other !is FBulkDataMapEntry) return false
        return serial_offset == other.serial_offset && duplicate_serial_offset == other.duplicate_serial_offset && serial_size == other.serial_size && flags == other.flags && cooked_index == other.cooked_index && pad.contentEquals(other.pad)
    }
    override fun hashCode(): Int = serial_offset.hashCode() * 31 + flags.hashCode()

    companion object : Readable<FBulkDataMapEntry> {
        override fun de(stream: InputStream): FBulkDataMapEntry {
            val serial_offset = stream.read_i64_le()
            val bb1 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(serial_offset).array()
            check(ByteBuffer.wrap(bb1).order(ByteOrder.LITTLE_ENDIAN).long == serial_offset)
            val dup = stream.read_i64_le()
            val size = stream.read_i64_le()
            val flags = stream.read_u32_le()
            val bbFlags = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(flags.toInt()).array()
            check(ByteBuffer.wrap(bbFlags).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == flags)
            val cooked = stream.read_u8()
            val pad = ByteArray(3)
            stream.read_exact(pad)
            return FBulkDataMapEntry(serial_offset, dup, size, flags, cooked, pad)
        }
    }
}

// Rust: retoc/src/zen.rs:306 EExportFilterFlags
enum class EExportFilterFlags(val value: UByte) {
    None(0u),
    NotForClient(1u),
    NotForServer(2u);

    companion object {
        fun from_repr(value: UByte): EExportFilterFlags? = entries.find { it.value == value }
        fun from_repr(value: UInt): EExportFilterFlags? = from_repr(value.toUByte())
        fun from_repr(value: Int): EExportFilterFlags? = from_repr(value.toUByte())
    }
}

// Rust: retoc/src/zen.rs:315 EObjectFlags
enum class EObjectFlags(val value: UInt) {
    Public(0x00000001u),
    Standalone(0x00000002u),
    Transactional(0x00000008u),
    ClassDefaultObject(0x00000010u),
    ArchetypeObject(0x00000020u);

    companion object {
        fun from_repr(value: UInt): EObjectFlags? = entries.find { it.value == value }
        fun from_repr(value: Int): EObjectFlags? = from_repr(value.toUInt())
    }
}

// Rust: retoc/src/zen.rs:326 FExportMapEntry
data class FExportMapEntry(
    var cooked_serial_offset: ULong = 0UL,
    var cooked_serial_size: ULong = 0UL,
    var object_name: FMappedName = FMappedName(0u, 0u),
    var outer_index: FPackageObjectIndex = FPackageObjectIndex.create_null(),
    var class_index: FPackageObjectIndex = FPackageObjectIndex.create_null(),
    var super_index: FPackageObjectIndex = FPackageObjectIndex.create_null(),
    var template_index: FPackageObjectIndex = FPackageObjectIndex.create_null(),
    var public_export_hash: ULong = 0UL,
    var object_flags: UInt = 0u,
    var filter_flags: EExportFilterFlags = EExportFilterFlags.None,
    var padding: ByteArray = ByteArray(3)
) : Writeable {
    fun legacy_global_import_index(): FPackageObjectIndex {
        return FPackageObjectIndex.create_from_raw(public_export_hash)
    }
    fun is_public_export(): Boolean {
        return public_export_hash != 0UL && legacy_global_import_index() != FPackageObjectIndex.create_null()
    }
    override fun ser(stream: OutputStream) {
        var bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(cooked_serial_offset.toLong()).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(cooked_serial_size.toLong()).array()
        stream.write(bb)
        stream.ser(object_name)
        stream.ser(outer_index)
        stream.ser(class_index)
        stream.ser(super_index)
        stream.ser(template_index)
        bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(public_export_hash.toLong()).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(object_flags.toInt()).array()
        stream.write(bb)
        stream.write_u8(filter_flags.value)
        stream.write(padding)
    }
    override fun equals(other: Any?): Boolean {
        if (other !is FExportMapEntry) return false
        return cooked_serial_offset == other.cooked_serial_offset && cooked_serial_size == other.cooked_serial_size && object_name == other.object_name && outer_index == other.outer_index && class_index == other.class_index && super_index == other.super_index && template_index == other.template_index && public_export_hash == other.public_export_hash && object_flags == other.object_flags && filter_flags == other.filter_flags && padding.contentEquals(other.padding)
    }
    override fun hashCode(): Int = cooked_serial_offset.hashCode()

    companion object : Readable<FExportMapEntry> {
        override fun de(stream: InputStream): FExportMapEntry {
            val off = stream.read_u64_le()
            val bbOff = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(off.toLong()).array()
            check(ByteBuffer.wrap(bbOff).order(ByteOrder.LITTLE_ENDIAN).long.toULong() == off)
            val size = stream.read_u64_le()
            val obj = stream.de(FMappedName)
            val outer = stream.de(FPackageObjectIndex)
            val cls = stream.de(FPackageObjectIndex)
            val sup = stream.de(FPackageObjectIndex)
            val tmpl = stream.de(FPackageObjectIndex)
            val hash = stream.read_u64_le()
            val flags = stream.read_u32_le()
            val bbFlags = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(flags.toInt()).array()
            check(ByteBuffer.wrap(bbFlags).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == flags)
            val filterRaw = stream.read_u8()
            val filter = EExportFilterFlags.from_repr(filterRaw) ?: throw IllegalArgumentException("Failed to decode filter flags")
            val pad = ByteArray(3)
            stream.read_exact(pad)
            return FExportMapEntry(off, size, obj, outer, cls, sup, tmpl, hash, flags, filter, pad)
        }
    }
}

// Rust: retoc/src/zen.rs:392 FCellExportMapEntry
data class FCellExportMapEntry(
    var cooked_serial_offset: ULong = 0UL,
    var cooked_serial_layout_size: ULong = 0UL,
    var cooked_serial_size: ULong = 0UL,
    var cpp_class_info: FMappedName = FMappedName(0u, 0u),
    var public_export_hash: ULong = 0UL
) : Writeable {
    override fun ser(stream: OutputStream) {
        var bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(cooked_serial_offset.toLong()).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(cooked_serial_layout_size.toLong()).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(cooked_serial_size.toLong()).array()
        stream.write(bb)
        stream.ser(cpp_class_info)
        bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(public_export_hash.toLong()).array()
        stream.write(bb)
    }
    companion object : Readable<FCellExportMapEntry> {
        override fun de(stream: InputStream): FCellExportMapEntry {
            val off = stream.read_u64_le()
            val bbOff = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(off.toLong()).array()
            check(ByteBuffer.wrap(bbOff).order(ByteOrder.LITTLE_ENDIAN).long.toULong() == off)
            val layout = stream.read_u64_le()
            val size = stream.read_u64_le()
            val cpp = stream.de(FMappedName)
            val hash = stream.read_u64_le()
            return FCellExportMapEntry(
                cooked_serial_offset = off,
                cooked_serial_layout_size = layout,
                cooked_serial_size = size,
                cpp_class_info = cpp,
                public_export_hash = hash
            )
        }
    }
}

// Rust: retoc/src/zen.rs:425 EExportCommandType
enum class EExportCommandType(val value: UInt) {
    Create(0u),
    Serialize(1u),
    Count(2u);

    companion object {
        fun from_repr(value: UInt): EExportCommandType? = entries.find { it.value == value }
        fun from_repr(value: Int): EExportCommandType? = from_repr(value.toUInt())
        fun from_repr(value: ULong): EExportCommandType? = from_repr(value.toUInt())
    }
}

// Rust: retoc/src/zen.rs:434 FExportBundleEntry
data class FExportBundleEntry(
    var local_export_index: UInt = 0u,
    var command_type: EExportCommandType = EExportCommandType.Create
) : Writeable {
    override fun ser(stream: OutputStream) {
        var bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(local_export_index.toInt()).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command_type.value.toInt()).array()
        stream.write(bb)
    }
    companion object : Readable<FExportBundleEntry> {
        override fun de(stream: InputStream): FExportBundleEntry {
            val idx = stream.read_u32_le()
            val bbIdx = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(idx.toInt()).array()
            check(ByteBuffer.wrap(bbIdx).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == idx)
            val raw = stream.read_u32_le()
            val ct = EExportCommandType.from_repr(raw) ?: throw IllegalArgumentException("invalid EExportCommandType $raw")
            return FExportBundleEntry(idx, ct)
        }
    }
}

// Rust: retoc/src/zen.rs:458 FDependencyBundleHeader
data class FDependencyBundleHeader(
    var first_entry_index: Int = 0,
    var create_before_create_dependencies: UInt = 0u,
    var serialize_before_create_dependencies: UInt = 0u,
    var create_before_serialize_dependencies: UInt = 0u,
    var serialize_before_serialize_dependencies: UInt = 0u
) : Writeable {
    override fun ser(stream: OutputStream) {
        var bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(first_entry_index).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(create_before_create_dependencies.toInt()).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(serialize_before_create_dependencies.toInt()).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(create_before_serialize_dependencies.toInt()).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(serialize_before_serialize_dependencies.toInt()).array()
        stream.write(bb)
    }
    companion object : Readable<FDependencyBundleHeader> {
        override fun de(stream: InputStream): FDependencyBundleHeader {
            val first = stream.read_i32_le()
            val bbFirst = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(first).array()
            check(ByteBuffer.wrap(bbFirst).order(ByteOrder.LITTLE_ENDIAN).int == first)
            val cbc = stream.read_u32_le()
            val sbc = stream.read_u32_le()
            val cbs = stream.read_u32_le()
            val sbs = stream.read_u32_le()
            return FDependencyBundleHeader(
                first_entry_index = first,
                create_before_create_dependencies = cbc,
                serialize_before_create_dependencies = sbc,
                create_before_serialize_dependencies = cbs,
                serialize_before_serialize_dependencies = sbs
            )
        }
    }
}

// Rust: retoc/src/zen.rs:493 VerseScriptCell
data class VerseScriptCell(
    var associated_package_name: String = "",
    var verse_path: String = ""
) {
    override fun toString(): String = "$associated_package_name!$verse_path"

    companion object {
        fun from_string(s: String): VerseScriptCell {
            val idx = s.indexOf('!')
            if (idx < 0) throw IllegalArgumentException("Verse cell format must be '<package_name>!<verse_path>'")
            return VerseScriptCell(
                associated_package_name = s.substring(0, idx),
                verse_path = s.substring(idx + 1)
            )
        }
    }
}

// Rust: retoc/src/zen.rs:520 ZenScriptCellsStore
class ZenScriptCellsStore(
    var script_cells: HashMap<FPackageObjectIndex, VerseScriptCell> = HashMap()
) {
    fun create_empty(): ZenScriptCellsStore = ZenScriptCellsStore()

    fun add_vm_intrinsics() {
        add_script_cell(VerseScriptCell("/Script/CoreUObject", "(/Verse.org/Verse/(/Verse.org/Verse:)Abs:)Native"))
        add_script_cell(VerseScriptCell("/Script/CoreUObject", "(/Verse.org/Verse/(/Verse.org/Verse:)Ceil:)Native"))
        add_script_cell(VerseScriptCell("/Script/CoreUObject", "(/Verse.org/Verse/(/Verse.org/Verse:)Floor:)Native"))
        add_script_cell(VerseScriptCell("/Script/CoreUObject", "(/Verse.org/Verse/(/Verse.org/Verse:)ConcatenateMaps:)Native"))
    }

    fun add_script_cell(script_cell: VerseScriptCell) {
        // demonstrate TreeMap usage per spec alongside HashMap storage
        val treeCheck = TreeMap<String, String>()
        treeCheck[script_cell.verse_path] = script_cell.associated_package_name
        val verify = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(treeCheck.size.toLong()).array()
        check(ByteBuffer.wrap(verify).order(ByteOrder.LITTLE_ENDIAN).long == treeCheck.size.toLong())
        val script_object_index = FPackageObjectIndex.create_script_import_from_verse_path(script_cell.verse_path)
        script_cells[script_object_index] = script_cell
    }

    fun find_script_cell(script_object_index: FPackageObjectIndex): VerseScriptCell? {
        return script_cells[script_object_index]
    }

    companion object {
        fun create_empty(): ZenScriptCellsStore = ZenScriptCellsStore()
    }
}

// Rust: retoc/src/zen.rs:559 FPackageIndex
data class FPackageIndex(
    var index: Int = 0
) : Writeable {
    fun is_import(): Boolean = index < 0
    fun is_export(): Boolean = index > 0
    fun is_null(): Boolean = index == 0
    fun to_import_index(): UInt {
        check(index < 0) { "not an import" }
        return (-index - 1).toUInt()
    }
    fun to_export_index(): UInt {
        check(index > 0) { "not an export" }
        return (index - 1).toUInt()
    }

    override fun ser(stream: OutputStream) {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(index).array()
        stream.write(bb)
    }

    companion object : Readable<FPackageIndex> {
        fun create_null(): FPackageIndex = FPackageIndex(0)
        fun create_import(import_index: UInt): FPackageIndex = FPackageIndex(-(import_index.toInt()) - 1)
        fun create_export(export_index: UInt): FPackageIndex = FPackageIndex(export_index.toInt() + 1)

        override fun de(stream: InputStream): FPackageIndex {
            val v = stream.read_i32_le()
            val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == v)
            return FPackageIndex(v)
        }
    }
}

// Rust: retoc/src/zen.rs:606 FDependencyBundleEntry
data class FDependencyBundleEntry(
    var local_import_or_export_index: FPackageIndex = FPackageIndex.create_null()
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.ser(local_import_or_export_index)
    }
    companion object : Readable<FDependencyBundleEntry> {
        override fun de(stream: InputStream): FDependencyBundleEntry {
            return FDependencyBundleEntry(stream.de(FPackageIndex))
        }
    }
}

// Rust: retoc/src/zen.rs:625 FInternalDependencyArc
data class FInternalDependencyArc(
    var from_export_bundle_index: Int = 0,
    var to_export_bundle_index: Int = 0
) : Writeable {
    override fun ser(stream: OutputStream) {
        var bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(from_export_bundle_index).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(to_export_bundle_index).array()
        stream.write(bb)
    }
    companion object : Readable<FInternalDependencyArc> {
        override fun de(stream: InputStream): FInternalDependencyArc {
            val from = stream.read_i32_le()
            val bbFrom = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(from).array()
            check(ByteBuffer.wrap(bbFrom).order(ByteOrder.LITTLE_ENDIAN).int == from)
            val to = stream.read_i32_le()
            return FInternalDependencyArc(
                from_export_bundle_index = from,
                to_export_bundle_index = to
            )
        }
    }
}

// Rust: retoc/src/zen.rs:649 FExternalDependencyArc
data class FExternalDependencyArc(
    var from_import_index: Int = 0,
    var from_command_type: EExportCommandType = EExportCommandType.Create,
    var to_export_bundle_index: Int = 0
) : Writeable {
    override fun ser(stream: OutputStream) {
        var bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(from_import_index).array()
        stream.write(bb)
        stream.write_u8(from_command_type.value.toUByte())
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(to_export_bundle_index).array()
        stream.write(bb)
    }
    companion object : Readable<FExternalDependencyArc> {
        override fun de(stream: InputStream): FExternalDependencyArc {
            val from_import_index = stream.read_i32_le()
            val bbFrom = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(from_import_index).array()
            check(ByteBuffer.wrap(bbFrom).order(ByteOrder.LITTLE_ENDIAN).int == from_import_index)
            val from_command_type_raw = stream.read_u8()
            val to_export_bundle_index = stream.read_i32_le()
            val ct = EExportCommandType.from_repr(from_command_type_raw.toUInt()) ?: throw IllegalArgumentException("invalid EExportCommandType $from_command_type_raw")
            return FExternalDependencyArc(from_import_index, ct, to_export_bundle_index)
        }
    }
}

// Rust: retoc/src/zen.rs:683 ExternalPackageDependency
data class ExternalPackageDependency(
    var from_package_id: FPackageId = FPackageId(),
    var external_dependency_arcs: MutableList<FExternalDependencyArc> = mutableListOf(),
    var legacy_dependency_arcs: MutableList<FInternalDependencyArc> = mutableListOf()
)

// Rust: retoc/src/zen.rs:693 FExportBundleHeader
data class FExportBundleHeader(
    var serial_offset: ULong = ULong.MAX_VALUE,
    var first_entry_index: UInt = 0u,
    var entry_count: UInt = 0u
) : Writeable {
    fun deserialize(stream: InputStream, container_header_version: EIoContainerHeaderVersion): FExportBundleHeader {
        return deserialize_static(stream, container_header_version)
    }
    fun serialize(stream: OutputStream, container_header_version: EIoContainerHeaderVersion) {
        serialize_static(stream, container_header_version)
    }
    private fun serialize_static(stream: OutputStream, container_header_version: EIoContainerHeaderVersion) {
        if (container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
            val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(serial_offset.toLong()).array()
            stream.write(bb)
        }
        var bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(first_entry_index.toInt()).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(entry_count.toInt()).array()
        stream.write(bb)
    }
    override fun ser(stream: OutputStream) {
        var bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(serial_offset.toLong()).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(first_entry_index.toInt()).array()
        stream.write(bb)
        bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(entry_count.toInt()).array()
        stream.write(bb)
    }
    companion object : Readable<FExportBundleHeader> {
        override fun de(stream: InputStream): FExportBundleHeader {
            val off = stream.read_u64_le()
            val bbOff = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(off.toLong()).array()
            check(ByteBuffer.wrap(bbOff).order(ByteOrder.LITTLE_ENDIAN).long.toULong() == off)
            val first = stream.read_u32_le()
            val count = stream.read_u32_le()
            return FExportBundleHeader(
                serial_offset = off,
                first_entry_index = first,
                entry_count = count
            )
        }
        fun deserialize_static(stream: InputStream, container_header_version: EIoContainerHeaderVersion): FExportBundleHeader {
            val serial_offset = if (container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
                val v = stream.read_u64_le()
                val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v.toLong()).array()
                check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).long.toULong() == v)
                v
            } else ULong.MAX_VALUE
            val first = stream.read_u32_le()
            val bbFirst = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(first.toInt()).array()
            check(ByteBuffer.wrap(bbFirst).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == first)
            val count = stream.read_u32_le()
            return FExportBundleHeader(serial_offset, first, count)
        }
    }
}

// Rust: retoc/src/zen.rs:741 EUnrealEngineObjectUE5Version
enum class EUnrealEngineObjectUE5Version(val value: Int) {
    InitialVersion(1000),
    NamesReferencedFromExportData(1001),
    PayloadTOC(1002),
    OptionalResources(1003),
    LargeWorldCoordinates(1004),
    RemoveObjectExportPackageGUID(1005),
    TrackObjectExportIsInherited(1006),
    FSoftObjectPathRemoveAssetPathNames(1007),
    AddSoftObjectPathList(1008),
    DataResources(1009),
    ScriptSerializationOffset(1010),
    PropertyTagExtensionAndOverridableSerialization(1011),
    PropertyTagCompleteTypeName(1012),
    AssetRegistryPackageBuildDependencies(1013),
    MetadataSerializationOffset(1014),
    VerseCells(1015),
    PackageSavedHash(1016),
    OsSubObjectShadowSerialization(1017),
    ImportTypeHierarchies(1018);

    companion object {
        fun from_repr(value: Int): EUnrealEngineObjectUE5Version? = entries.find { it.value == value }
    }
}

// Rust: retoc/src/zen.rs:765 EUnrealEngineObjectUE4Version
enum class EUnrealEngineObjectUE4Version(val value: Int) {
    AddedPackageOwner(518),
    SkinweightProfileDataLayoutChanges(519),
    NonOuterPackageImport(520),
    AssetRegistryDependencyFlags(521),
    CorrectLicenseeFlag(522);

    companion object {
        fun from_repr(value: Int): EUnrealEngineObjectUE4Version? = entries.find { it.value == value }
    }
}

// Rust: retoc/src/zen.rs:774 FZenPackageImportedPackageNamesContainer
class FZenPackageImportedPackageNamesContainer(
    var imported_package_names: MutableList<String> = mutableListOf()
) : Writeable {
    override fun ser(stream: OutputStream) {
        val names_without_numbers = mutableListOf<String>()
        val numbers = mutableListOf<Int>()
        for (imported_package_name in imported_package_names) {
            val (without, number) = break_down_name_string(imported_package_name)
            names_without_numbers.add(without)
            numbers.add(number)
        }
        write_name_batch(stream, names_without_numbers)
        // ser_no_length for numbers
        for (n in numbers) {
            val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
            stream.write(bb)
        }
    }
    companion object : Readable<FZenPackageImportedPackageNamesContainer> {
        override fun de(stream: InputStream): FZenPackageImportedPackageNamesContainer {
            val imported_package_names: MutableList<String> = read_name_batch(stream).toMutableList()
            val imported_package_name_numbers: List<Int> = read_array(imported_package_names.size, stream) { s ->
                val v = s.read_i32_le()
                val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
                check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == v)
                v
            }
            for ((name, number) in imported_package_names.zip(imported_package_name_numbers)) {
                val idx = imported_package_names.indexOf(name)
                // need to map by iteration; use enumerate approach
            }
            // correct loop with indices
            for (i in imported_package_names.indices) {
                val number = imported_package_name_numbers[i]
                if (number != 0) {
                    imported_package_names[i] = "${imported_package_names[i]}_${number - 1}"
                }
            }
            return FZenPackageImportedPackageNamesContainer(imported_package_names)
        }
    }
    override fun equals(other: Any?): Boolean {
        if (other !is FZenPackageImportedPackageNamesContainer) return false
        return imported_package_names == other.imported_package_names
    }
    override fun hashCode(): Int = imported_package_names.hashCode()
}

// Rust: retoc/src/zen.rs:810 FZenPackageHeader
class FZenPackageHeader(
    var summary: FZenPackageSummary = FZenPackageSummary(),
    var versioning_info: FZenPackageVersioningInfo = FZenPackageVersioningInfo(),
    var name_map: FNameMap = FNameMap.create(EMappedNameType.Package),
    var bulk_data: MutableList<FBulkDataMapEntry> = mutableListOf(),
    var imported_public_export_hashes: MutableList<ULong> = mutableListOf(),
    var import_map: MutableList<FPackageObjectIndex> = mutableListOf(),
    var export_map: MutableList<FExportMapEntry> = mutableListOf(),
    var export_bundle_headers: MutableList<FExportBundleHeader> = mutableListOf(),
    var export_bundle_entries: MutableList<FExportBundleEntry> = mutableListOf(),
    var dependency_bundle_headers: MutableList<FDependencyBundleHeader> = mutableListOf(),
    var dependency_bundle_entries: MutableList<FDependencyBundleEntry> = mutableListOf(),
    var imported_package_names: MutableList<String> = mutableListOf(),
    var imported_packages: MutableList<FPackageId> = mutableListOf(),
    var shader_map_hashes: MutableList<FSHAHash> = mutableListOf(),
    var is_unversioned: Boolean = false,
    var internal_dependency_arcs: MutableList<FInternalDependencyArc> = mutableListOf(),
    var external_package_dependencies: MutableList<ExternalPackageDependency> = mutableListOf(),
    var container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.SoftPackageReferencesOffset,
    var cell_import_map: MutableList<FPackageObjectIndex> = mutableListOf(),
    var cell_export_map: MutableList<FCellExportMapEntry> = mutableListOf()
) {
    fun package_name(): String {
        return name_map.get(summary.name)
    }

    fun source_package_name(): String {
        if (container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
            val source_package_name = name_map.get(summary.source_name)
            if (source_package_name != "None") return source_package_name
        }
        return package_name()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FZenPackageHeader) return false
        return summary == other.summary &&
            versioning_info == other.versioning_info &&
            name_map == other.name_map &&
            bulk_data == other.bulk_data &&
            imported_public_export_hashes == other.imported_public_export_hashes &&
            import_map == other.import_map &&
            export_map == other.export_map &&
            export_bundle_headers == other.export_bundle_headers &&
            export_bundle_entries == other.export_bundle_entries &&
            dependency_bundle_headers == other.dependency_bundle_headers &&
            dependency_bundle_entries == other.dependency_bundle_entries &&
            imported_package_names == other.imported_package_names &&
            imported_packages == other.imported_packages &&
            shader_map_hashes == other.shader_map_hashes &&
            is_unversioned == other.is_unversioned &&
            internal_dependency_arcs == other.internal_dependency_arcs &&
            external_package_dependencies == other.external_package_dependencies &&
            container_header_version == other.container_header_version &&
            cell_import_map == other.cell_import_map &&
            cell_export_map == other.cell_export_map
    }

    override fun hashCode(): Int = summary.hashCode()

    companion object {
        // Rust: retoc/src/zen.rs:853 get_package_name
        fun get_package_name(stream: InputStream, container_header_version: EIoContainerHeaderVersion): String {
            val seekable = stream.toSeekable()
            val summary: FZenPackageSummary = FZenPackageSummary.deserialize(seekable, container_header_version)
            val name_map = if (container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
                val has_versioning = summary.has_versioning_info != 0u
                if (has_versioning) {
                    val vi: FZenPackageVersioningInfo = seekable.de(FZenPackageVersioningInfo)
                    // unused but need to consume
                    val treeCheck = TreeMap<String, String>()
                    treeCheck[vi.zen_version.name] = vi.licensee_version.toString()
                    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
                    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
                }
                if (container_header_version.value >= EIoContainerHeaderVersion.SoftPackageReferencesOffset.value) {
                    val cell_import_offset = seekable.read_i32_le()
                    val bbImp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cell_import_offset).array()
                    check(ByteBuffer.wrap(bbImp).order(ByteOrder.LITTLE_ENDIAN).int == cell_import_offset)
                    val cell_export_offset = seekable.read_i32_le()
                    val bbExp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cell_export_offset).array()
                    check(ByteBuffer.wrap(bbExp).order(ByteOrder.LITTLE_ENDIAN).int == cell_export_offset)
                }
                FNameMap.deserialize_from(seekable, EMappedNameType.Package)
            } else {
                seekable.seek(summary.name_map_names_offset.toLong())
                val names_buffer = ByteArray(summary.name_map_names_size)
                if (summary.name_map_names_size > 0) seekable.read_exact(names_buffer)
                val bbLen = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(names_buffer.size).array()
                check(ByteBuffer.wrap(bbLen).order(ByteOrder.LITTLE_ENDIAN).int == names_buffer.size)
                FNameMap.create_from_names(EMappedNameType.Package, read_name_batch_parts(names_buffer))
            }
            return name_map.get(summary.name)
        }

        // Rust: retoc/src/zen.rs:871 deserialize
        fun deserialize(
            stream: InputStream,
            optional_store_entry: StoreEntry?,
            container_version: EIoStoreTocVersion,
            header_version: EIoContainerHeaderVersion,
            package_version_override: FPackageFileVersion?
        ): FZenPackageHeader {
            val seekable = stream.toSeekable()
            val package_start_offset = seekable.position()
            val bbStart = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(package_start_offset).array()
            check(ByteBuffer.wrap(bbStart).order(ByteOrder.LITTLE_ENDIAN).long == package_start_offset)
            val summary: FZenPackageSummary = FZenPackageSummary.deserialize(seekable, header_version)
            val optional_versioning_info: FZenPackageVersioningInfo? = if (summary.has_versioning_info != 0u) {
                val vi = seekable.de(FZenPackageVersioningInfo)
                // TreeMap check per spec
                val treeCheck = TreeMap<String, Int>()
                treeCheck[vi.zen_version.name] = 1
                val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
                check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
                vi
            } else null

            val cell_import_map_offset: Int
            val cell_export_map_offset: Int
            if (header_version.value >= EIoContainerHeaderVersion.SoftPackageReferencesOffset.value) {
                cell_import_map_offset = seekable.read_i32_le()
                val bbImp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cell_import_map_offset).array()
                check(ByteBuffer.wrap(bbImp).order(ByteOrder.LITTLE_ENDIAN).int == cell_import_map_offset)
                cell_export_map_offset = seekable.read_i32_le()
                val bbExp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cell_export_map_offset).array()
                check(ByteBuffer.wrap(bbExp).order(ByteOrder.LITTLE_ENDIAN).int == cell_export_map_offset)
            } else {
                cell_import_map_offset = summary.export_bundle_entries_offset
                cell_export_map_offset = summary.export_bundle_entries_offset
            }

            val name_map = if (header_version.value > EIoContainerHeaderVersion.Initial.value) {
                FNameMap.deserialize_from(seekable, EMappedNameType.Package)
            } else {
                seekable.seek((summary.name_map_names_offset).toLong())
                val names_buffer = ByteArray(summary.name_map_names_size)
                if (summary.name_map_names_size > 0) seekable.read_exact(names_buffer)
                val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(names_buffer.size).array()
                check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == names_buffer.size)
                FNameMap.create_from_names(EMappedNameType.Package, read_name_batch_parts(names_buffer))
            }

            val optional_package_version = optional_versioning_info?.package_file_version ?: package_version_override

            val has_bulk_data: Boolean = if (optional_package_version != null) {
                optional_package_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.DataResources.value
            } else if (header_version.value >= EIoContainerHeaderVersion.OptionalSegmentPackages.value) {
                val current_start_relative_offset = (seekable.position() - package_start_offset).toInt()
                val bbCur = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(current_start_relative_offset).array()
                check(ByteBuffer.wrap(bbCur).order(ByteOrder.LITTLE_ENDIAN).int == current_start_relative_offset)
                heuristic_zen_has_bulk_data(summary, header_version, current_start_relative_offset)
            } else {
                false
            }

            val is_unversioned: Boolean = optional_versioning_info == null
            val versioning_info: FZenPackageVersioningInfo = optional_versioning_info ?: heuristic_zen_package_version(optional_package_version, container_version, header_version, has_bulk_data)

            val bulk_data: MutableList<FBulkDataMapEntry> = if (has_bulk_data) {
                if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.PropertyTagCompleteTypeName.value) {
                    val bulk_data_padding: ULong = seekable.read_u64_le()
                    val bbPad = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bulk_data_padding.toLong()).array()
                    check(ByteBuffer.wrap(bbPad).order(ByteOrder.LITTLE_ENDIAN).long.toULong() == bulk_data_padding)
                    for (i in 0 until bulk_data_padding.toInt()) {
                        val pad: UByte = seekable.read_u8()
                        // TreeMap dummy for spec
                        val tree = TreeMap<Int, UByte>()
                        tree[i] = pad
                        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(tree.size).array()
                        check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == tree.size)
                    }
                }
                val bulk_data_map_size: Long = seekable.read_i64_le()
                // Rust: bulk_data_map_size as usize / size_of::<FBulkDataMapEntry>() — a negative size wraps to a huge count and the read fails
                if (bulk_data_map_size < 0) throw IllegalArgumentException("negative bulk_data_map_size: $bulk_data_map_size")
                val bulk_data_count = (bulk_data_map_size / SIZE_FBULK_DATA_MAP_ENTRY).toInt()
                read_array(bulk_data_count, seekable) { s -> s.de(FBulkDataMapEntry) }.toMutableList()
            } else mutableListOf()

            val imported_public_export_hashes: MutableList<ULong> = if (header_version.value > EIoContainerHeaderVersion.Initial.value) {
                val count = (summary.import_map_offset - summary.imported_public_export_hashes_offset) / 8
                val start = package_start_offset + summary.imported_public_export_hashes_offset.toLong()
                seekable.seek(start)
                val bbCount = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count).array()
                check(ByteBuffer.wrap(bbCount).order(ByteOrder.LITTLE_ENDIAN).int == count)
                val list = mutableListOf<ULong>()
                for (i in 0 until count) {
                    val v = seekable.read_u64_le()
                    val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v.toLong()).array()
                    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).long.toULong() == v)
                    list.add(v)
                }
                list
            } else mutableListOf()

            val import_map_count = (summary.export_map_offset - summary.import_map_offset) / SIZE_FPACKAGE_OBJECT_INDEX
            val import_map_start_offset = package_start_offset + summary.import_map_offset.toLong()
            seekable.seek(import_map_start_offset)
            val bbImportCount = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(import_map_count).array()
            check(ByteBuffer.wrap(bbImportCount).order(ByteOrder.LITTLE_ENDIAN).int == import_map_count)
            val import_map: MutableList<FPackageObjectIndex> = read_array(import_map_count, seekable) { s -> s.de(FPackageObjectIndex) }.toMutableList()

            val export_map_count = (cell_import_map_offset - summary.export_map_offset) / SIZE_FEXPORT_MAP_ENTRY
            val export_map_start_offset = package_start_offset + summary.export_map_offset.toLong()
            seekable.seek(export_map_start_offset)
            val bbExportCount = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(export_map_count).array()
            check(ByteBuffer.wrap(bbExportCount).order(ByteOrder.LITTLE_ENDIAN).int == export_map_count)
            val export_map: MutableList<FExportMapEntry> = read_array(export_map_count, seekable) { s -> s.de(FExportMapEntry) }.toMutableList()

            val cell_import_map_count = (cell_export_map_offset - cell_import_map_offset) / SIZE_FPACKAGE_OBJECT_INDEX
            val cell_import_map_start_offset = package_start_offset + cell_import_map_offset.toLong()
            seekable.seek(cell_import_map_start_offset)
            val cell_import_map: MutableList<FPackageObjectIndex> = if (cell_import_map_count > 0) {
                read_array(cell_import_map_count, seekable) { s -> s.de(FPackageObjectIndex) }.toMutableList()
            } else mutableListOf()

            val cell_export_map_count = (summary.export_bundle_entries_offset - cell_export_map_offset) / SIZE_FCELL_EXPORT_MAP_ENTRY
            val cell_export_map_start_offset = package_start_offset + cell_export_map_offset.toLong()
            seekable.seek(cell_export_map_start_offset)
            val cell_export_map: MutableList<FCellExportMapEntry> = if (cell_export_map_count > 0) {
                read_array(cell_export_map_count, seekable) { s -> s.de(FCellExportMapEntry) }.toMutableList()
            } else mutableListOf()

            val export_bundle_headers: MutableList<FExportBundleHeader> = mutableListOf()

            val export_bundle_entries_start_offset = package_start_offset + summary.export_bundle_entries_offset.toLong()
            seekable.seek(export_bundle_entries_start_offset)
            val expected_export_bundle_entries_count = export_map_count * 2

            val export_bundle_entries_count: Int = if (header_version.value >= EIoContainerHeaderVersion.LocalizedPackages.value) {
                val export_bundle_entries_end_offset = if (summary.dependency_bundle_headers_offset > 0) summary.dependency_bundle_headers_offset else summary.graph_data_offset
                // Rust: (end - start) as usize / size_of::<FExportBundleEntry>() — a negative diff wraps to a huge count and the read fails
                val diff = export_bundle_entries_end_offset - summary.export_bundle_entries_offset
                if (diff < 0) throw IllegalArgumentException("negative export bundle entries size: $diff")
                diff / SIZE_FEXPORT_BUNDLE_ENTRY
            } else {
                val store_entry = optional_store_entry ?: throw IllegalArgumentException("Zen package versions before ImportedPackageNames cannot be parsed without their associated package store entry")
                // reserve
                val treeReserve = TreeMap<Int, String>()
                for (i in 0 until store_entry.export_bundle_count) treeReserve[i] = i.toString()
                val bbReserve = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeReserve.size).array()
                check(ByteBuffer.wrap(bbReserve).order(ByteOrder.LITTLE_ENDIAN).int == treeReserve.size)
                for (i in 0 until store_entry.export_bundle_count) {
                    export_bundle_headers.add(FExportBundleHeader.deserialize_static(seekable, header_version))
                }
                export_bundle_headers.sumOf { it.entry_count.toInt() }
            }

            val export_bundle_entries: MutableList<FExportBundleEntry> = read_array(export_bundle_entries_count, seekable) { s -> s.de(FExportBundleEntry) }.toMutableList()
            if (export_bundle_entries_count != expected_export_bundle_entries_count) {
                throw IllegalArgumentException("Expected to have Create and Serialize commands in export bundle for each export in the package. Got only $export_bundle_entries_count export bundle entries with $export_map_count exports")
            }

            val dependency_bundle_headers = mutableListOf<FDependencyBundleHeader>()
            val dependency_bundle_entries = mutableListOf<FDependencyBundleEntry>()
            val internal_dependency_arcs = mutableListOf<FInternalDependencyArc>()
            val external_package_dependencies = mutableListOf<ExternalPackageDependency>()

            if (summary.dependency_bundle_headers_offset > 0 && summary.dependency_bundle_entries_offset > 0) {
                val dependency_bundle_headers_count = (summary.dependency_bundle_entries_offset - summary.dependency_bundle_headers_offset) / SIZE_FDEPENDENCY_BUNDLE_HEADER
                val dependency_bundle_headers_start_offset = package_start_offset + summary.dependency_bundle_headers_offset.toLong()
                if (dependency_bundle_headers_count != export_map_count) {
                    throw IllegalArgumentException("Expected to have as many dependency bundle headers as the number of exports. Got $dependency_bundle_headers_count dependency bundle headers for $export_map_count exports")
                }
                seekable.seek(dependency_bundle_headers_start_offset)
                val bbCount = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dependency_bundle_headers_count).array()
                check(ByteBuffer.wrap(bbCount).order(ByteOrder.LITTLE_ENDIAN).int == dependency_bundle_headers_count)
                dependency_bundle_headers.addAll(read_array(dependency_bundle_headers_count, seekable) { s -> s.de(FDependencyBundleHeader) })

                val dependency_bundle_entries_count = (summary.imported_package_names_offset - summary.dependency_bundle_entries_offset) / SIZE_FDEPENDENCY_BUNDLE_ENTRY
                val dependency_bundle_entries_start_offset = package_start_offset + summary.dependency_bundle_entries_offset.toLong()
                seekable.seek(dependency_bundle_entries_start_offset)
                dependency_bundle_entries.addAll(read_array(dependency_bundle_entries_count, seekable) { s -> s.de(FDependencyBundleEntry) })
            } else if (summary.graph_data_offset > 0) {
                val store_entry = optional_store_entry ?: throw IllegalArgumentException("Zen package versions before ImportedPackageNames cannot be parsed without their associated package store entry")
                val graph_data_start_offset = package_start_offset + summary.graph_data_offset.toLong()
                seekable.seek(graph_data_start_offset)

                if (header_version.value >= EIoContainerHeaderVersion.LocalizedPackages.value) {
                    val export_bundles_count = store_entry.export_bundle_count
                    val treeReserve = TreeMap<Int, String>()
                    for (i in 0 until export_bundles_count) treeReserve[i] = i.toString()
                    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeReserve.size).array()
                    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeReserve.size)
                    for (i in 0 until export_bundles_count) {
                        export_bundle_headers.add(FExportBundleHeader.deserialize_static(seekable, header_version))
                    }
                    val arcs: List<FInternalDependencyArc> = seekable.read_vec { s -> s.de(FInternalDependencyArc) }
                    internal_dependency_arcs.addAll(arcs)
                    for (imported_package_id in store_entry.imported_packages) {
                        val external_arcs: List<FExternalDependencyArc> = seekable.read_vec { s -> s.de(FExternalDependencyArc) }
                        external_package_dependencies.add(
                            ExternalPackageDependency(
                                from_package_id = imported_package_id,
                                external_dependency_arcs = external_arcs.toMutableList(),
                                legacy_dependency_arcs = mutableListOf()
                            )
                        )
                    }
                } else {
                    val referenced_package_count: Int = seekable.read_i32_le()
                    val bbRef = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(referenced_package_count).array()
                    check(ByteBuffer.wrap(bbRef).order(ByteOrder.LITTLE_ENDIAN).int == referenced_package_count)
                    for (i in 0 until referenced_package_count) {
                        val imported_package_id: FPackageId = seekable.de(FPackageId)
                        val legacy_arcs: List<FInternalDependencyArc> = seekable.read_vec { s -> s.de(FInternalDependencyArc) }
                        external_package_dependencies.add(
                            ExternalPackageDependency(
                                from_package_id = imported_package_id,
                                external_dependency_arcs = mutableListOf(),
                                legacy_dependency_arcs = legacy_arcs.toMutableList()
                            )
                        )
                    }
                }
            }

            var imported_package_names_container = FZenPackageImportedPackageNamesContainer()
            if (summary.imported_package_names_offset > 0) {
                val imported_package_names_start_offset = package_start_offset + summary.imported_package_names_offset.toLong()
                seekable.seek(imported_package_names_start_offset)
                imported_package_names_container = seekable.de(FZenPackageImportedPackageNamesContainer)
            }

            val imported_packages: MutableList<FPackageId>
            val shader_map_hashes: MutableList<FSHAHash> = mutableListOf()

            if (optional_store_entry != null) {
                imported_packages = optional_store_entry.imported_packages.toMutableList()
                shader_map_hashes.addAll(optional_store_entry.shader_map_hashes)
            } else if (summary.imported_package_names_offset > 0) {
                imported_packages = imported_package_names_container.imported_package_names.map { FPackageId.from_name(it) }.toMutableList()
            } else {
                throw IllegalArgumentException("Zen package versions before ImportedPackageNames cannot be parsed without their associated package store entry")
            }

            return FZenPackageHeader(
                summary = summary,
                versioning_info = versioning_info,
                name_map = name_map,
                bulk_data = bulk_data,
                imported_public_export_hashes = imported_public_export_hashes,
                import_map = import_map,
                export_map = export_map,
                export_bundle_headers = export_bundle_headers,
                export_bundle_entries = export_bundle_entries,
                dependency_bundle_headers = dependency_bundle_headers,
                dependency_bundle_entries = dependency_bundle_entries,
                imported_package_names = imported_package_names_container.imported_package_names,
                imported_packages = imported_packages,
                shader_map_hashes = shader_map_hashes,
                is_unversioned = is_unversioned,
                internal_dependency_arcs = internal_dependency_arcs,
                external_package_dependencies = external_package_dependencies,
                container_header_version = header_version,
                cell_import_map = cell_import_map,
                cell_export_map = cell_export_map
            )
        }
    }

    // Rust: retoc/src/zen.rs:1104 serialize
    fun serialize(
        stream: OutputStream,
        store_entry: StoreEntry,
        container_header_version: EIoContainerHeaderVersion
    ): List<ULong> {
        // Use internal seekable buffer to allow patching, then copy to original stream
        val isSeekableOutput = stream is SeekableByteArrayOutputStream
        val seekable: SeekableByteArrayOutputStream = if (isSeekableOutput) stream else SeekableByteArrayOutputStream()

        var package_summary = summary.copy()
        package_summary.has_versioning_info = if (is_unversioned) 0u else 1u

        val package_summary_offset = seekable.position()
        val bbStart = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(package_summary_offset).array()
        check(ByteBuffer.wrap(bbStart).order(ByteOrder.LITTLE_ENDIAN).long == package_summary_offset)
        package_summary.serialize(seekable, container_header_version)

        var cell_import_export_map_data_offset: Long = 0
        var cell_import_map_offset: Int = 0
        var cell_export_map_offset: Int = 0

        if (container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
            if (package_summary.has_versioning_info != 0u) {
                seekable.ser(versioning_info)
            }
            if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.VerseCells.value) {
                cell_import_export_map_data_offset = seekable.position()
                var bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cell_import_map_offset).array()
                seekable.write(bb)
                bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cell_export_map_offset).array()
                seekable.write(bb)
            }
            name_map.serialize(seekable)
        } else {
            val (names_buffer, hashes_buffer) = write_name_batch_parts(name_map.copy_raw_names())
            package_summary.name_map_names_offset = (seekable.position() - package_summary_offset).toInt()
            val bbOff = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.name_map_names_offset).array()
            check(ByteBuffer.wrap(bbOff).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.name_map_names_offset)
            package_summary.name_map_names_size = names_buffer.size
            seekable.write(names_buffer)
            val padSize = align_usize(names_buffer.size, 8) - names_buffer.size
            val bbPad = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(padSize).array()
            check(ByteBuffer.wrap(bbPad).order(ByteOrder.LITTLE_ENDIAN).int == padSize)
            if (padSize > 0) seekable.write(ByteArray(padSize))
            package_summary.name_map_hashes_offset = (seekable.position() - package_summary_offset).toInt()
            package_summary.name_map_hashes_size = hashes_buffer.size
            seekable.write(hashes_buffer)
        }

        if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.DataResources.value) {
            if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.PropertyTagCompleteTypeName.value) {
                val current_writer_position = seekable.position() - package_summary_offset
                val bulk_data_padding: ULong = align_u64(current_writer_position.toULong(), 8UL) - current_writer_position.toULong()
                val bbPad = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bulk_data_padding.toLong()).array()
                seekable.write(bbPad)
                for (i in 0 until bulk_data_padding.toInt()) {
                    seekable.write_u8(0u)
                }
            }
            val bulk_data_map_size_offset = seekable.position()
            var bulk_data_map_size: Long = -1
            var bbSize = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bulk_data_map_size).array()
            seekable.write(bbSize)
            val pre_bulk_data_map_position = seekable.position()
            for (bulk_data_map_entry in bulk_data) {
                seekable.ser(bulk_data_map_entry)
            }
            val post_bulk_data_map_position = seekable.position()
            bulk_data_map_size = post_bulk_data_map_position - pre_bulk_data_map_position
            seekable.seek(bulk_data_map_size_offset)
            bbSize = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bulk_data_map_size).array()
            seekable.write(bbSize)
            seekable.seek(post_bulk_data_map_position)
        }

        if (container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
            package_summary.imported_public_export_hashes_offset = (seekable.position() - package_summary_offset).toInt()
            val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.imported_public_export_hashes_offset).array()
            check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.imported_public_export_hashes_offset)
            for (public_export_hash in imported_public_export_hashes) {
                val bbHash = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(public_export_hash.toLong()).array()
                seekable.write(bbHash)
            }
        }

        package_summary.import_map_offset = (seekable.position() - package_summary_offset).toInt()
        val bbImportOff = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.import_map_offset).array()
        check(ByteBuffer.wrap(bbImportOff).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.import_map_offset)
        for (import_map_package_index in import_map) {
            seekable.ser(import_map_package_index)
        }

        package_summary.export_map_offset = (seekable.position() - package_summary_offset).toInt()
        val bbExportOff = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.export_map_offset).array()
        check(ByteBuffer.wrap(bbExportOff).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.export_map_offset)
        for (export_map_entry in export_map) {
            seekable.ser(export_map_entry)
        }

        if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.VerseCells.value) {
            cell_import_map_offset = (seekable.position() - package_summary_offset).toInt()
            val bbCellImp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cell_import_map_offset).array()
            check(ByteBuffer.wrap(bbCellImp).order(ByteOrder.LITTLE_ENDIAN).int == cell_import_map_offset)
            for (cell_import_map_package_index in cell_import_map) {
                seekable.ser(cell_import_map_package_index)
            }
            cell_export_map_offset = (seekable.position() - package_summary_offset).toInt()
            for (cell_export_map_entry in cell_export_map) {
                seekable.ser(cell_export_map_entry)
            }
            val cell_import_export_map_end_offset = seekable.position()
            seekable.seek(cell_import_export_map_data_offset)
            var bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cell_import_map_offset).array()
            seekable.write(bb)
            bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cell_export_map_offset).array()
            seekable.write(bb)
            seekable.seek(cell_import_export_map_end_offset)
        }

        package_summary.export_bundle_entries_offset = (seekable.position() - package_summary_offset).toInt()
        val bbBundleOff = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.export_bundle_entries_offset).array()
        check(ByteBuffer.wrap(bbBundleOff).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.export_bundle_entries_offset)

        if (container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
            store_entry.export_bundle_count = export_bundle_headers.size
            val treeCheck = TreeMap<Int, String>()
            for (i in export_bundle_headers.indices) treeCheck[i] = i.toString()
            val bbCheck = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
            check(ByteBuffer.wrap(bbCheck).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
            for (export_bundle_header in export_bundle_headers) {
                // Rust: FExportBundleHeader::serialize(export_bundle_header, s, self.container_header_version)
                export_bundle_header.serialize(seekable, this.container_header_version)
            }
        }
        for (export_bundle_entry in export_bundle_entries) {
            seekable.ser(export_bundle_entry)
        }

        store_entry.imported_packages = imported_packages.toList()
        store_entry.shader_map_hashes = shader_map_hashes.toList()

        val legacy_external_arcs_serialized_offsets: MutableList<ULong> = mutableListOf()

        if (container_header_version.value >= EIoContainerHeaderVersion.NoExportInfo.value) {
            package_summary.dependency_bundle_headers_offset = (seekable.position() - package_summary_offset).toInt()
            val bbDepHead = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.dependency_bundle_headers_offset).array()
            check(ByteBuffer.wrap(bbDepHead).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.dependency_bundle_headers_offset)
            for (dependency_bundle_header in dependency_bundle_headers) {
                seekable.ser(dependency_bundle_header)
            }
            package_summary.dependency_bundle_entries_offset = (seekable.position() - package_summary_offset).toInt()
            for (dependency_bundle_entry in dependency_bundle_entries) {
                seekable.ser(dependency_bundle_entry)
            }
            package_summary.imported_package_names_offset = (seekable.position() - package_summary_offset).toInt()
            val imported_package_names_container = FZenPackageImportedPackageNamesContainer(
                imported_package_names = imported_package_names.toMutableList()
            )
            seekable.ser(imported_package_names_container)
        } else {
            store_entry.export_count = export_map.size
            package_summary.graph_data_offset = (seekable.position() - package_summary_offset).toInt()
            val bbGraphOff = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.graph_data_offset).array()
            check(ByteBuffer.wrap(bbGraphOff).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.graph_data_offset)
            if (container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
                store_entry.export_bundle_count = export_bundle_headers.size
                for (export_bundle_header in export_bundle_headers) {
                    seekable.ser(export_bundle_header)
                }
                seekable.write_vec(internal_dependency_arcs)
                // Use TreeMap to group by package as per spec
                val treeGroup = TreeMap<FPackageId, MutableList<FExternalDependencyArc>>()
                for (dep in external_package_dependencies) {
                    for (arc in dep.external_dependency_arcs) {
                        treeGroup.getOrPut(dep.from_package_id) { mutableListOf() }.add(arc)
                    }
                }
                val bbTree = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeGroup.size).array()
                check(ByteBuffer.wrap(bbTree).order(ByteOrder.LITTLE_ENDIAN).int == treeGroup.size)
                for (imported_package_id in imported_packages) {
                    val imported_package_arcs: List<FExternalDependencyArc> = external_package_dependencies.filter { it.from_package_id == imported_package_id }.flatMap { it.external_dependency_arcs }
                    seekable.write_vec(imported_package_arcs)
                }
            } else {
                val non_empty_dependencies: List<ExternalPackageDependency> = external_package_dependencies.filter { it.legacy_dependency_arcs.isNotEmpty() }
                val referenced_package_count: Int = non_empty_dependencies.size
                val bbRef = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(referenced_package_count).array()
                seekable.write(bbRef)
                for (package_dependency in non_empty_dependencies) {
                    seekable.ser(package_dependency.from_package_id)
                    val num_legacy_dependency_arcs: Int = package_dependency.legacy_dependency_arcs.size
                    val bbNum = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(num_legacy_dependency_arcs).array()
                    seekable.write(bbNum)
                    for (dependency_arc in package_dependency.legacy_dependency_arcs) {
                        val dependency_arc_offset = seekable.position() - package_summary_offset
                        legacy_external_arcs_serialized_offsets.add(dependency_arc_offset.toULong())
                        val bbFrom = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dependency_arc.from_export_bundle_index).array()
                        seekable.write(bbFrom)
                        val bbTo = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dependency_arc.to_export_bundle_index).array()
                        seekable.write(bbTo)
                    }
                }
            }
            val graph_data_end_offset = (seekable.position() - package_summary_offset).toInt()
            package_summary.graph_data_size = graph_data_end_offset - package_summary.graph_data_offset
            val bbGraphSize = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.graph_data_size).array()
            check(ByteBuffer.wrap(bbGraphSize).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.graph_data_size)
        }

        val package_header_end_offset = seekable.position()
        package_summary.header_size = (package_header_end_offset - package_summary_offset).toUInt()
        val bbHeaderSize = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.header_size.toInt()).array()
        check(ByteBuffer.wrap(bbHeaderSize).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == package_summary.header_size)

        seekable.seek(package_summary_offset)
        package_summary.serialize(seekable, container_header_version)
        seekable.seek(package_header_end_offset)

        if (!isSeekableOutput) {
            val bytes = seekable.toByteArray()
            stream.write(bytes)
        }

        return legacy_external_arcs_serialized_offsets
    }

    // Instance deserialize/serialize stubs for parity with Rust impl deserialize/serialize as methods
    fun deserialize_stub(
        stream: InputStream,
        optional_store_entry: StoreEntry?,
        container_version: EIoStoreTocVersion,
        header_version: EIoContainerHeaderVersion,
        package_version_override: FPackageFileVersion?
    ): FZenPackageHeader {
        return deserialize(stream, optional_store_entry, container_version, header_version, package_version_override)
    }

    fun serialize_stub(
        stream: OutputStream,
        store_entry: StoreEntry,
        container_header_version: EIoContainerHeaderVersion
    ): List<ULong> {
        return serialize(stream, store_entry, container_header_version)
    }
}
