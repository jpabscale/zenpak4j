// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/version.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "EnumEntryName")

package com.github.jpabscale.zenpak4j.retoc

// Rust: retoc/src/version.rs:8 EngineVersion
enum class EngineVersion {
    UE4_25,
    UE4_26,
    UE4_27,
    UE5_0,
    UE5_1,
    UE5_2,
    UE5_3,
    UE5_4,
    UE5_5,
    UE5_6,
    UE5_7;

    // Rust: retoc/src/version.rs:25 toc_version
    fun toc_version(): EIoStoreTocVersion {
        return when (this) {
            UE4_25 -> EIoStoreTocVersion.DirectoryIndex
            UE4_26 -> EIoStoreTocVersion.DirectoryIndex
            UE4_27 -> EIoStoreTocVersion.PartitionSize
            UE5_0 -> EIoStoreTocVersion.PerfectHashWithOverflow
            UE5_1 -> EIoStoreTocVersion.PerfectHashWithOverflow
            UE5_2 -> EIoStoreTocVersion.PerfectHashWithOverflow
            UE5_3 -> EIoStoreTocVersion.PerfectHashWithOverflow
            UE5_4 -> EIoStoreTocVersion.OnDemandMetaData
            UE5_5 -> EIoStoreTocVersion.ReplaceIoChunkHashWithIoHash
            UE5_6 -> EIoStoreTocVersion.ReplaceIoChunkHashWithIoHash
            UE5_7 -> EIoStoreTocVersion.ReplaceIoChunkHashWithIoHash
        }
    }

    // Rust: retoc/src/version.rs:41 container_header_version
    fun container_header_version(): EIoContainerHeaderVersion {
        return when (this) {
            UE4_25 -> EIoContainerHeaderVersion.Initial
            UE4_26 -> EIoContainerHeaderVersion.Initial
            UE4_27 -> EIoContainerHeaderVersion.Initial
            UE5_0 -> EIoContainerHeaderVersion.LocalizedPackages
            UE5_1 -> EIoContainerHeaderVersion.OptionalSegmentPackages
            UE5_2 -> EIoContainerHeaderVersion.OptionalSegmentPackages
            UE5_3 -> EIoContainerHeaderVersion.NoExportInfo
            UE5_4 -> EIoContainerHeaderVersion.NoExportInfo
            UE5_5 -> EIoContainerHeaderVersion.SoftPackageReferences
            UE5_6 -> EIoContainerHeaderVersion.SoftPackageReferencesOffset
            UE5_7 -> EIoContainerHeaderVersion.SoftPackageReferencesOffset
        }
    }

    // Rust: retoc/src/version.rs:57 object_ue4_version
    fun object_ue4_version(): EUnrealEngineObjectUE4Version {
        return when (this) {
            UE4_25 -> EUnrealEngineObjectUE4Version.AddedPackageOwner
            UE4_26 -> EUnrealEngineObjectUE4Version.CorrectLicenseeFlag
            UE4_27 -> EUnrealEngineObjectUE4Version.CorrectLicenseeFlag
            else -> throw IllegalArgumentException("object_ue4_version unreachable for $this")
        }
    }

    // Rust: retoc/src/version.rs:66 object_ue5_version
    fun object_ue5_version(): EUnrealEngineObjectUE5Version {
        return when (this) {
            UE5_0 -> EUnrealEngineObjectUE5Version.LargeWorldCoordinates
            UE5_1 -> EUnrealEngineObjectUE5Version.AddSoftObjectPathList
            UE5_2 -> EUnrealEngineObjectUE5Version.DataResources
            UE5_3 -> EUnrealEngineObjectUE5Version.DataResources
            UE5_4 -> EUnrealEngineObjectUE5Version.PropertyTagCompleteTypeName
            UE5_5 -> EUnrealEngineObjectUE5Version.AssetRegistryPackageBuildDependencies
            UE5_6 -> EUnrealEngineObjectUE5Version.OsSubObjectShadowSerialization
            UE5_7 -> EUnrealEngineObjectUE5Version.ImportTypeHierarchies
            else -> throw IllegalArgumentException("object_ue5_version unreachable for $this")
        }
    }

    // Rust: retoc/src/version.rs:80 package_file_version
    fun package_file_version(): FPackageFileVersion {
        return if (this < EngineVersion.UE5_0) {
            FPackageFileVersion.create_ue4(this.object_ue4_version())
        } else {
            FPackageFileVersion.create_ue5(this.object_ue5_version())
        }
    }

    companion object {
        fun from_string(value: String): EngineVersion? = entries.find { it.name == value }
    }
}

// ---------------------------------------------------------------------------
// Supporting enums/classes required by version.rs
// Rust: retoc/src/lib.rs:1162 EIoStoreTocVersion and retoc/src/container_header.rs:291 EIoContainerHeaderVersion
// Moved to lib.kt for 1:1 parity; re-exported via typealiases for backwards compatibility
// (Actual definitions live in lib.kt to avoid duplicate class errors)
// EIoStoreTocVersion and EIoContainerHeaderVersion are defined in lib.kt

// Rust: retoc/src/zen.rs:741 EUnrealEngineObjectUE5Version, EUnrealEngineObjectUE4Version, FPackageFileVersion
// Moved to zen.kt for 1:1 parity (retoc/src/zen.rs:1)
// Definitions now live in zen.kt to avoid duplicate class errors; EngineVersion methods reference them via same package
