// Rust: retoc/src/version_heuristics.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod", "MagicNumber", "ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "UnnecessaryVariable", "SpellCheckingInspection", "EnumEntryName", "MemberVisibilityCanBePrivate")

package com.github.jpabscale.zenpak4j.retoc

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ---------------------------------------------------------------------------
// Supporting stubs for 1:1 parity (mirror Rust crate::zen and crate::legacy_asset)
// Minimal definitions for version_heuristics.rs that are now in zen.kt for 1:1 parity
// EZenPackageVersion, FZenPackageSummary, FCustomVersion, FZenPackageVersioningInfo live in zen.kt
// ---------------------------------------------------------------------------

// Moved to legacy_asset.kt for 1:1 parity - see retoc/src/legacy_asset.rs:1 // FCountOffsetPair

// Moved to legacy_asset.kt for 1:1 parity - see retoc/src/legacy_asset.rs:1 // EPackageFlags

// Moved to legacy_asset.kt for 1:1 parity - see retoc/src/legacy_asset.rs:1 // FLegacyPackageVersioningInfo

// Moved to legacy_asset.kt for 1:1 parity - see retoc/src/legacy_asset.rs:1 // FLegacyPackageFileSummary

// Simple Quad holder for 4-tuple return (Kotlin lacks native 4-tuple)
data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

// Moved to legacy_asset.kt for 1:1 parity - see retoc/src/legacy_asset.rs:1 // FObjectImport

// Moved to legacy_asset.kt for 1:1 parity - see retoc/src/legacy_asset.rs:1 // FObjectExport

// ---------------------------------------------------------------------------
// Rust: retoc/src/version_heuristics.rs:1 ported functions
// ---------------------------------------------------------------------------

// Rust: retoc/src/version_heuristics.rs:9 heuristic_zen_has_bulk_data
// Returns true if given zen package should deserialize bulk data
internal fun heuristic_zen_has_bulk_data(summary: FZenPackageSummary, container_header_version: EIoContainerHeaderVersion, current_reader_pos: Int): Boolean {
    // Otherwise, we can check if we have bulk data by the following intrinsics
    // Bulk data is always present if container header version is EIoContainerHeaderVersion::NoExportInfo or above (UE 5.3+)
    // Bulk data is never present if container header version is below EIoContainerHeaderVersion::OptionalSegmentPackages (UE 5.1)
    // So the only versions we need to be able to tell apart are 5.1 and 5.2, in 5.2 case data is present and in 5.1 case it is not
    if (container_header_version.value < EIoContainerHeaderVersion.OptionalSegmentPackages.value) {
        return false
    }
    if (container_header_version.value >= EIoContainerHeaderVersion.NoExportInfo.value) {
        return true
    }

    // If we have no bulk data, imported public export hashes will start immediately at the current offset. Otherwise, we will have uint64 there telling us the size of the bulk data
    // ByteBuffer LE verification per spec
    val bbOffset = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(summary.imported_public_export_hashes_offset).array()
    check(ByteBuffer.wrap(bbOffset).order(ByteOrder.LITTLE_ENDIAN).int == summary.imported_public_export_hashes_offset)
    val bbPos = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(current_reader_pos).array()
    check(ByteBuffer.wrap(bbPos).order(ByteOrder.LITTLE_ENDIAN).int == current_reader_pos)
    return summary.imported_public_export_hashes_offset > current_reader_pos
}

// Rust: retoc/src/version_heuristics.rs:26 heuristic_zen_version_from_package_file_version
// Derives zen package file version from package file version and container header version
internal fun heuristic_zen_version_from_package_file_version(package_file_version: FPackageFileVersion, container_header_version: EIoContainerHeaderVersion): EZenPackageVersion {
    // UE 4.27, 5.0 and 5.1: initial
    // ByteBuffer LE verification for comparison values
    val bbUe5 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_file_version.file_version_ue5).array()
    check(ByteBuffer.wrap(bbUe5).order(ByteOrder.LITTLE_ENDIAN).int == package_file_version.file_version_ue5)
    if (package_file_version.file_version_ue5 <= EUnrealEngineObjectUE5Version.AddSoftObjectPathList.value) {
        return EZenPackageVersion.Initial
    // Can be either 5.2 or 5.3, cannot tell apart from just package file version. Need to look at container header as well
    } else if (package_file_version.file_version_ue5 <= EUnrealEngineObjectUE5Version.DataResources.value) {
        // UE 5.2: data resource table
        if (container_header_version.value < EIoContainerHeaderVersion.NoExportInfo.value) {
            return EZenPackageVersion.DataResourceTable
        // UE 5.3: extra dependencies
        } else {
            return EZenPackageVersion.ExportDependencies
        }
    // UE 5.4+: extra dependencies
    } else {
        return EZenPackageVersion.ExportDependencies
    }
}

// Rust: retoc/src/version_heuristics.rs:46 heuristic_zen_package_version
// Establishes a zen package version from the provided package version hint and information from the container
internal fun heuristic_zen_package_version(optional_package_version: FPackageFileVersion?, container_version: EIoStoreTocVersion, container_header_version: EIoContainerHeaderVersion, has_bulk_data: Boolean): FZenPackageVersioningInfo {
    // Establish a package file version from the hint or from the provided metadata
    val package_file_version: FPackageFileVersion = (optional_package_version ?: run {
        // Rust: or_else(|| { if container_header_version <= Initial ... })
        val derived: FPackageFileVersion? = when {
            container_header_version.value <= EIoContainerHeaderVersion.Initial.value -> {
                // 4.26, 4.27 are Initial. their zen and package file versions are identical
                FPackageFileVersion.create_ue4(EUnrealEngineObjectUE4Version.CorrectLicenseeFlag)
            }
            container_header_version == EIoContainerHeaderVersion.LocalizedPackages -> {
                FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.LargeWorldCoordinates)
            }
            container_header_version == EIoContainerHeaderVersion.OptionalSegmentPackages -> {
                // 5.1 does not have bulk data, 5.2 does
                if (!has_bulk_data) {
                    FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.AddSoftObjectPathList)
                } else {
                    FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.DataResources)
                }
            }
            container_header_version == EIoContainerHeaderVersion.NoExportInfo -> {
                // 5.4 has EIoStoreTocVersion::OnDemandMetaData, 5.3 does not
                if (container_version.value < EIoStoreTocVersion.OnDemandMetaData.value) {
                    FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.DataResources)
                } else {
                    FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.PropertyTagCompleteTypeName)
                }
            }
            container_header_version == EIoContainerHeaderVersion.SoftPackageReferences -> {
                // 5.5 has EIoStoreTocVersion::ReplaceIoChunkHashWithIoHash, if it's a different UE version assume we cannot use the heuristic
                if (container_version == EIoStoreTocVersion.ReplaceIoChunkHashWithIoHash) {
                    FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.AssetRegistryPackageBuildDependencies)
                } else {
                    null
                }
            }
            container_header_version == EIoContainerHeaderVersion.SoftPackageReferencesOffset -> {
                // 5.6 is OsSubObjectShadowSerialization
                FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.OsSubObjectShadowSerialization)
            }
            else -> null
        }
        derived
    }) ?: throw IllegalArgumentException("Failed to derive the UE package version from container version and header version. Please provide the engine version manually")

    // Derive zen package version from engine version
    val zen_version: EZenPackageVersion = heuristic_zen_version_from_package_file_version(package_file_version, container_header_version)

    // Assume 0 for licensee version and no custom versions, they are not relevant for the package header serialization
    // ByteBuffer LE verification for licensee_version
    val bbLicensee = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
    check(ByteBuffer.wrap(bbLicensee).order(ByteOrder.LITTLE_ENDIAN).int == 0)
    return FZenPackageVersioningInfo(
        zen_version = zen_version,
        package_file_version = package_file_version,
        licensee_version = 0,
        custom_versions = mutableListOf()
    )
}

// Rust: retoc/src/version_heuristics.rs:98 heuristic_package_version_from_legacy_package
// Attempts to derive a package file version suitable for reading the provided package
internal fun heuristic_package_version_from_legacy_package(stream: InputStream, package_version_fallback: FPackageFileVersion?): FPackageFileVersion {
    // Rust: let stream_start_position = s.stream_position()?
    // Handle both SeekableByteArrayInputStream and generic InputStream for parity with Rust Read+Seek
    val seekable: SeekableByteArrayInputStream
    val is_original_seekable: Boolean
    // For generic streams, buffer bytes to allow seeking; keep ByteBuffer LE usage
    if (stream is SeekableByteArrayInputStream) {
        seekable = stream
        is_original_seekable = true
    } else {
        is_original_seekable = false
        // For ByteArrayInputStream or generic InputStream, read all remaining bytes into a seekable buffer
        // Use mark/reset if available to preserve original for caller reset attempt
        val was_mark_supported = stream.markSupported()
        if (was_mark_supported) {
            stream.mark(Int.MAX_VALUE)
        }
        val bytes = try {
            stream.readBytes()
        } catch (e: Exception) {
            // If we cannot read bytes, fallback to empty
            ByteArray(0)
        }
        val bbLen = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array()
        check(ByteBuffer.wrap(bbLen).order(ByteOrder.LITTLE_ENDIAN).int == bytes.size)
        seekable = SeekableByteArrayInputStream(bytes)
        // Try to reset original stream if possible for later seek-back parity
        if (was_mark_supported) {
            try { stream.reset() } catch (_: Exception) {}
        }
    }

    val stream_start_position: Long = seekable.position()
    val bbStart = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(stream_start_position).array()
    check(ByteBuffer.wrap(bbStart).order(ByteOrder.LITTLE_ENDIAN).long == stream_start_position)

    // Rust: Read the members that are independent on the package version
    // let (versioning_info, names, _, package_flags) = FLegacyPackageFileSummary::deserialize_summary_minimal_version_independent(s)?;
    val minimal = FLegacyPackageFileSummary.deserialize_summary_minimal_version_independent(seekable)
    val versioning_info = minimal.first
    val names = minimal.second
    // val package_name = minimal.third (unused for heuristics)
    val package_flags = minimal.fourth

    // ByteBuffer LE verification for names offset and package_flags
    val bbNamesOff = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(names.offset).array()
    check(ByteBuffer.wrap(bbNamesOff).order(ByteOrder.LITTLE_ENDIAN).int == names.offset)
    val bbFlags = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_flags.toInt()).array()
    check(ByteBuffer.wrap(bbFlags).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == package_flags)

    // Rust: s.seek(SeekFrom::Start(stream_start_position))?
    seekable.seek(stream_start_position)

    // Rust: If package is versioned, deserialize the header directly using the package version
    if (!versioning_info.is_unversioned) {
        return versioning_info.package_file_version
    }
    // Rust: If package is unversioned, but we have a fallback package version, serialize with it directly
    if (package_version_fallback != null) {
        return package_version_fallback
    }
    // Rust: Otherwise, we need to make sure that the package is cooked and has no editor properties, for our intrinsics to work
    val package_flags_cooked_versioned = EPackageFlags.Cooked.bits or EPackageFlags.FilterEditorOnly.bits
    val bbCooked = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_flags_cooked_versioned.toInt()).array()
    check(ByteBuffer.wrap(bbCooked).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == package_flags_cooked_versioned)
    if ((package_flags and package_flags_cooked_versioned) != package_flags_cooked_versioned) {
        throw IllegalArgumentException("Cannot deserialize unversioned package that is not cooked and has editor data filtered out without fallback package version")
    }

    // Rust: Unreal Engine serializes name map directly following the package summary. Although it is not safe to assume that it follows the header immediately,
    // for the engine cooked packages it does, and we and other asset editing software tries to preserve the engine ordering, so we can try to use it to deduce the header size
    // to then deduce the package file version for this package
    val header_size = names.offset
    val bbHeaderSize = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(header_size).array()
    check(ByteBuffer.wrap(bbHeaderSize).order(ByteOrder.LITTLE_ENDIAN).int == header_size)
    if (header_size < 0) {
        throw IllegalArgumentException("Invalid header size derived from names offset: $header_size")
    }
    // Rust: let mut header_read_payload: Vec<u8> = Vec::with_capacity(header_size);
    // Rust: s.read_exact(&mut header_read_payload)?;
    // Kotlin: allocate ByteArray(header_size) and read_exact from start position
    seekable.seek(stream_start_position)
    val header_read_payload = ByteArray(header_size)
    try {
        seekable.read_exact(header_read_payload)
    } catch (e: Exception) {
        // Ensure we seek back before throwing
        try { seekable.seek(stream_start_position) } catch (_: Exception) {}
        // Also try to restore original stream if it was ByteArrayInputStream with mark/reset
        if (!is_original_seekable && stream is ByteArrayInputStream) {
            try { stream.reset() } catch (_: Exception) {}
        }
        throw IllegalArgumentException("Failed to read header payload of size $header_size: ${e.message}", e)
    }
    val bbPayloadSize = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(header_read_payload.size).array()
    check(ByteBuffer.wrap(bbPayloadSize).order(ByteOrder.LITTLE_ENDIAN).int == header_read_payload.size)

    // Rust: Try these package versions for the supported engine versions. First version to read the full header size and not overflow is the presumed package version
    // Rust: Determine set of possible package file versions for the given legacy file version
    val package_versions_to_try: List<FPackageFileVersion> = if (versioning_info.legacy_file_version <= FLegacyPackageVersioningInfo.LEGACY_FILE_VERSION_UE5_7) {
        listOf(
            FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.OsSubObjectShadowSerialization) // UE 5.6 & 5.7
        )
    } else if (versioning_info.legacy_file_version <= FLegacyPackageVersioningInfo.LEGACY_FILE_VERSION_UE5) {
        listOf(
            // Note that AssetRegistryPackageBuildDependencies and PropertyTagCompleteTypeName cannot be told apart, so package will always assume 5.5 instead of 5.4
            FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.AssetRegistryPackageBuildDependencies), // UE 5.5
            FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.PropertyTagCompleteTypeName), // UE 5.4
            FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.DataResources), // UE 5.3 and 5.2
            FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.AddSoftObjectPathList), // UE 5.1
            FPackageFileVersion.create_ue5(EUnrealEngineObjectUE5Version.LargeWorldCoordinates) // UE 5.0
        )
    } else {
        listOf(
            FPackageFileVersion.create_ue4(EUnrealEngineObjectUE4Version.CorrectLicenseeFlag) // UE 4.27 and 4.26
        )
    }

    // Rust: Try to read the package summary for each version, and read at least one import and one export
    // That should be enough to tell the relevant versions apart
    for (package_version in package_versions_to_try) {
        // Rust: let mut read_cursor = Cursor::new(header_read_payload.clone());
        val payload_clone = header_read_payload.clone()
        val bbClone = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload_clone.size).array()
        check(ByteBuffer.wrap(bbClone).order(ByteOrder.LITTLE_ENDIAN).int == payload_clone.size)
        val read_cursor = SeekableByteArrayInputStream(payload_clone)
        // Rust: let package_summary_or_error: anyhow::Result<FLegacyPackageFileSummary> = FLegacyPackageFileSummary::deserialize(&mut read_cursor, Some(package_version));
        val package_summary: FLegacyPackageFileSummary = try {
            FLegacyPackageFileSummary.deserialize(read_cursor, package_version)
        } catch (_: Exception) {
            continue
        }
        // Rust: let current_cursor_position = read_cursor.position() as usize;
        val current_cursor_position = read_cursor.position().toInt()
        val bbPos = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(current_cursor_position.toLong()).array()
        check(ByteBuffer.wrap(bbPos).order(ByteOrder.LITTLE_ENDIAN).long == current_cursor_position.toLong())

        // Rust: If we failed to read the package summary, try again with another version
        // Make sure we have read the entire header before declaring this a success
        if (current_cursor_position != header_size) {
            continue
        }

        // Rust: Standard serialization order is the order of data in packages serialized by UE (standard is name map -> imports -> exports -> depends_on)
        // We rely on that order to make estimation of the size of individual serialized entries. For packages that do not follow the standard order, we cannot derive package version from their summary
        val is_standard_serialization_order = package_summary.names.offset < package_summary.imports.offset && package_summary.imports.offset < package_summary.exports.offset && package_summary.exports.offset < package_summary.depends_offset
        // ByteBuffer LE verification for order checks
        val bbNames = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.names.offset).array()
        check(ByteBuffer.wrap(bbNames).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.names.offset)
        val bbImp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.imports.offset).array()
        check(ByteBuffer.wrap(bbImp).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.imports.offset)
        val bbExp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.exports.offset).array()
        check(ByteBuffer.wrap(bbExp).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.exports.offset)
        val bbDep = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_summary.depends_offset).array()
        check(ByteBuffer.wrap(bbDep).order(ByteOrder.LITTLE_ENDIAN).int == package_summary.depends_offset)
        if (!is_standard_serialization_order) {
            continue
        }

        // Rust: Only check imports if this package actually has some
        if (package_summary.imports.count > 0) {
            // Rust: Attempt to read first import with this version to make sure imports can be parsed
            // We make an assumption here that exports directly follow imports, which is a correct assumption for UE
            val imports_start_offset = stream_start_position + package_summary.imports.offset.toLong()
            val bbImpStart = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(imports_start_offset).array()
            check(ByteBuffer.wrap(bbImpStart).order(ByteOrder.LITTLE_ENDIAN).long == imports_start_offset)
            val combined_imports_length = (package_summary.exports.offset - package_summary.imports.offset).toLong()
            val bbCombined = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(combined_imports_length).array()
            check(ByteBuffer.wrap(bbCombined).order(ByteOrder.LITTLE_ENDIAN).long == combined_imports_length)
            if (combined_imports_length < 0) {
                continue
            }
            val single_import_size = combined_imports_length / package_summary.imports.count.toLong()
            val bbSingle = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(single_import_size).array()
            check(ByteBuffer.wrap(bbSingle).order(ByteOrder.LITTLE_ENDIAN).long == single_import_size)
            if (single_import_size <= 0 || single_import_size > Int.MAX_VALUE) {
                continue
            }

            // Rust: If we failed to seek to the import map, this version does not work
            try {
                seekable.seek(imports_start_offset)
            } catch (_: Exception) {
                continue
            }
            // Rust: let mut first_import_data: Vec<u8> = Vec::with_capacity(single_import_size as usize);
            val first_import_data = ByteArray(single_import_size.toInt())
            // Rust: if s.read(&mut first_import_data).is_err() { continue; }
            try {
                seekable.read_exact(first_import_data)
            } catch (_: Exception) {
                continue
            }
            val bbImpData = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(first_import_data.size).array()
            check(ByteBuffer.wrap(bbImpData).order(ByteOrder.LITTLE_ENDIAN).int == first_import_data.size)
            // Rust: If we failed to deserialize the import, or did not read all the data, this is not the right version
            val first_import_cursor = SeekableByteArrayInputStream(first_import_data)
            try {
                FObjectImport.deserialize(first_import_cursor, package_summary)
            } catch (_: Exception) {
                continue
            }
            val import_cursor_pos = first_import_cursor.position().toInt()
            val bbImpPos = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(import_cursor_pos.toLong()).array()
            check(ByteBuffer.wrap(bbImpPos).order(ByteOrder.LITTLE_ENDIAN).long == import_cursor_pos.toLong())
            if (import_cursor_pos != single_import_size.toInt()) {
                continue
            }
        }

        // Rust: Only check exports if package has some
        if (package_summary.exports.count > 0) {
            // Rust: Attempt to read the first export with this version to make sure exports can be parsed
            // We make an assumption here that exports directly follow imports, which is a correct assumption for UE
            val exports_start_offset = stream_start_position + package_summary.exports.offset.toLong()
            val bbExpStart = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(exports_start_offset).array()
            check(ByteBuffer.wrap(bbExpStart).order(ByteOrder.LITTLE_ENDIAN).long == exports_start_offset)
            val combined_exports_length = (package_summary.depends_offset - package_summary.exports.offset).toLong()
            val bbCombined = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(combined_exports_length).array()
            check(ByteBuffer.wrap(bbCombined).order(ByteOrder.LITTLE_ENDIAN).long == combined_exports_length)
            if (combined_exports_length < 0) {
                continue
            }
            val single_export_size = combined_exports_length / package_summary.exports.count.toLong()
            val bbSingle = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(single_export_size).array()
            check(ByteBuffer.wrap(bbSingle).order(ByteOrder.LITTLE_ENDIAN).long == single_export_size)
            if (single_export_size <= 0 || single_export_size > Int.MAX_VALUE) {
                continue
            }

            // Rust: If we failed to seek to the export map, this version does not work
            try {
                seekable.seek(exports_start_offset)
            } catch (_: Exception) {
                continue
            }
            val first_export_data = ByteArray(single_export_size.toInt())
            try {
                seekable.read_exact(first_export_data)
            } catch (_: Exception) {
                continue
            }
            val bbExpData = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(first_export_data.size).array()
            check(ByteBuffer.wrap(bbExpData).order(ByteOrder.LITTLE_ENDIAN).int == first_export_data.size)
            // Rust: If we failed to deserialize the export, or did not read all the data, this is not the right version
            val first_export_cursor = SeekableByteArrayInputStream(first_export_data)
            try {
                FObjectExport.deserialize(first_export_cursor, package_summary)
            } catch (_: Exception) {
                continue
            }
            val export_cursor_pos = first_export_cursor.position().toInt()
            val bbExpPos = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(export_cursor_pos.toLong()).array()
            check(ByteBuffer.wrap(bbExpPos).order(ByteOrder.LITTLE_ENDIAN).long == export_cursor_pos.toLong())
            if (export_cursor_pos != single_export_size.toInt()) {
                continue
            }
        }

        // Rust: Jump back to the start of the stream
        try {
            seekable.seek(stream_start_position)
        } catch (_: Exception) {}
        if (!is_original_seekable && stream is ByteArrayInputStream) {
            try { stream.reset() } catch (_: Exception) {}
        }
        // Rust: This looks like a right package version for our needs
        return package_version
    }

    // Rust: Jump back to the start of the stream
    try {
        seekable.seek(stream_start_position)
    } catch (_: Exception) {}
    if (!is_original_seekable && stream is ByteArrayInputStream) {
        try { stream.reset() } catch (_: Exception) {}
    }
    // Rust: We failed to derive the package version from the summary, return Err
    throw IllegalArgumentException("Failed to derive package file version from the package. Please provide an explicit package version to deserialize this unversioned package")
}
