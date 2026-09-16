// Ported from repak (MIT OR Apache-2.0) — Copyright (c) 2024 Truman Kilen, spuds
//@parity:on EXC-014
// Rust: repak_cli/src/main.rs — action layer shared by repak-cli and :zenpak (EXC-014).
// Moved verbatim from repak-cli/main.kt; the Clikt commands wrap calls in try/catch(handle_error)
// mirroring Rust main.rs's Result -> exit handling (the action fns themselves return/throw like Rust's).
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "SpellCheckingInspection", "MagicNumber", "TooManyFunctions", "LongMethod", "ComplexMethod", "EnumEntryName", "VariableNaming")

package com.github.jpabscale.zenpak4j.repak_actions

import com.github.jpabscale.zenpak4j.repak.Compression
import com.github.jpabscale.zenpak4j.repak.PakBuilder
import com.github.jpabscale.zenpak4j.repak.RepakContext
import com.github.jpabscale.zenpak4j.repak.get_game_id
import com.github.jpabscale.zenpak4j.repak.RepakError
import com.github.jpabscale.zenpak4j.repak.Version
import java.io.File
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.TreeMap
import javax.crypto.spec.SecretKeySpec

// Kotlin-only helper (EXC-001/EXC-005): capture the game-id from the calling thread's
// global into an explicit context so parallelStream workers see it without ThreadLocals.
private fun current_context(): RepakContext = RepakContext(get_game_id(null))

// ---------------------------------------------------------------------------
// Rust: repak_cli/src/main.rs:17 struct ActionInfo
// ---------------------------------------------------------------------------
// Rust: repak_cli/src/main.rs:17
data class ActionInfo(
    var input: String
)

// Rust: repak_cli/src/main.rs:24 struct ActionList
// Rust: repak_cli/src/main.rs:24
data class ActionList(
    var input: String,
    var strip_prefix: String = "../../../"
)

// Rust: repak_cli/src/main.rs:35 struct ActionHashList
// Rust: repak_cli/src/main.rs:35
data class ActionHashList(
    var input: String,
    var strip_prefix: String = "../../../"
)

// Rust: repak_cli/src/main.rs:46 struct ActionUnpack
// Rust: repak_cli/src/main.rs:46
data class ActionUnpack(
    var input: List<String>,
    var output: String? = null,
    var strip_prefix: String = "../../../",
    var verbose: Boolean = false,
    var quiet: Boolean = false,
    var force: Boolean = false,
    var include: List<String> = emptyList()
)

// Rust: repak_cli/src/main.rs:77 struct ActionPack
// Rust: repak_cli/src/main.rs:77
data class ActionPack(
    var input: String,
    var output: String? = null,
    var mount_point: String = "../../../",
    var version: Version = Version.V8B,
    var compression: Compression? = null,
    var path_hash_seed: ULong = 0u,
    var verbose: Boolean = false,
    var quiet: Boolean = false
)

// Rust: repak_cli/src/main.rs:119 struct ActionGet
// Rust: repak_cli/src/main.rs:119
data class ActionGet(
    var input: String,
    var file: String,
    var strip_prefix: String = "../../../"
)

// Rust: repak_cli/src/main.rs:134 enum Action
// Rust: repak_cli/src/main.rs:134
sealed class Action {
    data class Info(val inner: ActionInfo) : Action()
    data class ListAction(val inner: ActionList) : Action()
    data class HashList(val inner: ActionHashList) : Action()
    data class Unpack(val inner: ActionUnpack) : Action()
    data class Pack(val inner: ActionPack) : Action()
    data class Get(val inner: ActionGet) : Action()
}

// Rust: repak_cli/src/main.rs:150 struct Args
// Rust: repak_cli/src/main.rs:150
data class Args(
    var aes_key: AesKey? = null,
    var game_id: String? = null,
    var action: Action
)

// Rust: repak_cli/src/main.rs:165 struct AesKey
// Rust: repak_cli/src/main.rs:165
class AesKey(val key: SecretKeySpec) {
    companion object {
        // Rust: repak_cli/src/main.rs:167 impl FromStr for AesKey
        fun from_string(s: String): AesKey {
            // Rust: strip_prefix "0x", hex::decode, base64::STANDARD_NO_PAD.decode
            fun try_parse(bytes: ByteArray): AesKey? {
                return try {
                    // aes::Aes256::new_from_slice(&bytes).ok().map(AesKey)
                    if (bytes.size != 32) return null
                    AesKey(SecretKeySpec(bytes, "AES"))
                } catch (_: Exception) {
                    null
                }
            }
            val hex_stripped = if (s.startsWith("0x")) s.substring(2) else s
            // try hex decode
            try {
                val hex_bytes = hex_decode(hex_stripped)
                val parsed = try_parse(hex_bytes)
                if (parsed != null) return parsed
            } catch (_: Exception) {
                // fall through to base64
            }
            // try base64 decode (STANDARD_NO_PAD, trim trailing '=' then decode)
            try {
                val trimmed = s.trimEnd('=')
                // pad to multiple of 4
                val padded = when (trimmed.length % 4) {
                    2 -> trimmed + "=="
                    3 -> trimmed + "="
                    else -> trimmed
                }
                val b64 = Base64.getDecoder().decode(padded)
                val parsed = try_parse(b64)
                if (parsed != null) return parsed
            } catch (_: Exception) {
            }
            throw RepakError.Aes
        }

        private fun hex_decode(s: String): ByteArray {
            val clean = s.trim()
            require(clean.length % 2 == 0) { "hex length must be even" }
            return ByteArray(clean.length / 2) { i ->
                val hi = Character.digit(clean[i * 2], 16)
                val lo = Character.digit(clean[i * 2 + 1], 16)
                require(hi != -1 && lo != -1) { "invalid hex" }
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}

// Rust: repak_cli/src/main.rs:311 const STYLE
// Rust: repak_cli/src/main.rs:311
const val STYLE: String = "[{elapsed_precise}] [{wide_bar}] {pos}/{len} ({eta})"

// Rust: repak_cli/src/main.rs:313 enum Output
// Rust: repak_cli/src/main.rs:313
sealed class Output {
    data class Progress(val bar: Any) : Output()
    object Stdout : Output()

    fun println(msg: String) {
        when (this) {
            is Progress -> kotlin.io.println(msg)
            is Stdout -> kotlin.io.println(msg)
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers mirroring Rust path handling
// ---------------------------------------------------------------------------


private fun to_slash_lossy(path: Path): String {
    return path.toString().replace('\\', '/')
}

private fun clean(path: Path): Path {
    return path.normalize()
}

private fun strip_prefix(path: Path, prefix: Path): Path {
    val prefixStr = prefix.toString()
    // Empty prefix -> return path as-is (matches Rust Path::new("").strip_prefix("") -> Ok(path))
    if (prefixStr.isEmpty()) {
        return path
    }
    // Normalize both
    val normPath = path.normalize()
    val normPrefix = prefix.normalize()
    if (normPrefix.toString().isEmpty()) {
        return normPath
    }
    if (!normPath.startsWith(normPrefix)) {
        throw RepakError.PrefixMismatch(prefix = prefix.toString(), path = path.toString())
    }
    // relativize
    return try {
        normPrefix.relativize(normPath)
    } catch (ex: Exception) {
        throw RepakError.PrefixMismatch(prefix = prefix.toString(), path = path.toString())
    }
}

//@parity:on EXC-006
private fun split_paths(input: String): List<String> {
    val sep = File.pathSeparator
    return input.split(sep).filter { it.isNotEmpty() }
}
//@parity:off EXC-006

private fun hex_encode(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun format_guid(guid: ULong?): String {
    return if (guid == null) "None" else "Some(${guid.toString(16).uppercase().padStart(32, '0')})"
}

private fun format_seed(seed: ULong?): String {
    return if (seed == null) "None" else "Some(${seed.toString(16).uppercase().padStart(8, '0')})"
}



// Include glob matching helper
private fun matches_include(stripped: Path, includes: List<String>): Boolean {
    if (includes.isEmpty()) return true
    for (pattern in includes) {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        val strippedNorm = stripped.normalize()
        // check full file path
        if (matcher.matches(strippedNorm)) return true
        // check ancestor directories
        var current: Path? = strippedNorm.parent
        while (current != null) {
            if (matcher.matches(current)) return true
            // hack to check ancestor directories with trailing slash
            val withSlash = Paths.get(current.toString() + "/")
            try {
                if (matcher.matches(withSlash)) return true
                // also try without slash normalized?
                if (matcher.matches(withSlash.normalize())) return true
            } catch (_: Exception) {}
            // also try withSlash as string path with slash
            current = current.parent
        }
        // Edge: if stripped has no parent, also check empty?
    }
    return false
}

// ---------------------------------------------------------------------------
// Rust: repak_cli/src/main.rs:200 fn info
// ---------------------------------------------------------------------------
fun info(aes_key: AesKey?, action: ActionInfo) {
        // Rust: let mut builder = repak::PakBuilder::new();
        var builder = PakBuilder.new()
        // Rust: if let Some(aes_key) = aes_key { builder = builder.key(aes_key); }
        if (aes_key != null) {
            builder = builder.key(aes_key.key)
        }
        // Rust: let pak = builder.reader(&mut BufReader::new(File::open(action.input)?))?;
        FileChannel.open(Paths.get(action.input), StandardOpenOption.READ).use { channel ->
            val pak = builder.reader(channel, current_context())
            // Rust: println!("mount point: {}", pak.mount_point());
            println("mount point: ${pak.mount_point()}")
            // Rust: println!("version: {}", pak.version());
            println("version: ${pak.version()}")
            // Rust: println!("version major: {}", pak.version().version_major());
            println("version major: ${pak.version().version_major()}")
            // Rust: println!("encrypted index: {}", pak.encrypted_index());
            println("encrypted index: ${pak.encrypted_index()}")
            // Rust: println!("encrytion guid: {:032X?}", pak.encryption_guid());
            println("encrytion guid: ${format_guid(pak.encryption_guid())}")
            // Rust: let compression = pak.used_compression();
            val compression = pak.used_compression()
            // Rust: if compression.is_empty() { println!("compression: None"); } else { println!("compression: {}", compression.iter().join(" ,")); }
            if (compression.isEmpty()) {
                println("compression: None")
            } else {
                println("compression: ${compression.joinToString(" ,")}")
            }
            // Rust: println!("path hash seed: {:08X?}", pak.path_hash_seed());
            println("path hash seed: ${format_seed(pak.path_hash_seed())}")
            // Rust: println!("{} file entries", pak.files().len());
            println("${pak.files().size} file entries")
        }
}

// Rust: repak_cli/src/main.rs:222 fn list
fun list(aes_key: AesKey?, action: ActionList) {
        // Rust: let mut builder = repak::PakBuilder::new();
        var builder = PakBuilder.new()
        // Rust: if let Some(aes_key) = aes_key { builder = builder.key(aes_key); }
        if (aes_key != null) {
            builder = builder.key(aes_key.key)
        }
        // Rust: let pak = builder.reader(&mut BufReader::new(File::open(action.input)?))?;
        FileChannel.open(Paths.get(action.input), StandardOpenOption.READ).use { channel ->
            val pak = builder.reader(channel, current_context())
            // Rust: let mount_point = PathBuf::from(pak.mount_point());
            val mount_point = Paths.get(pak.mount_point())
            // Rust: let prefix = Path::new(&action.strip_prefix);
            val prefix = Paths.get(action.strip_prefix)
            // Rust: let full_paths = pak.files().into_iter().map(|f| mount_point.join(f)).collect::<Vec<_>>();
            val full_paths = pak.files().map { f -> mount_point.resolve(f) }
            // Rust: let stripped = full_paths.iter().map(|f| f.strip_prefix(prefix).map_err(|_| PrefixMismatch { path: f.to_string_lossy().to_string(), prefix: prefix.to_string_lossy().to_string(), })).collect::<Result<Vec<_>, _>>()?;
            val stripped = full_paths.map { f ->
                try {
                    strip_prefix(f, prefix)
                } catch (ex: RepakError.PrefixMismatch) {
                    // Preserve original Rust error creation with original prefix string for accurate message
                    throw RepakError.PrefixMismatch(prefix = action.strip_prefix, path = f.toString())
                }
            }
            // Rust: for f in stripped { println!("{}", f.to_slash_lossy()); }
            for (f in stripped) {
                println(to_slash_lossy(f))
            }
        }
}

// Rust: repak_cli/src/main.rs:255 fn hash_list
fun hash_list(aes_key: AesKey?, action: ActionHashList) {
        // Rust: let mut builder = repak::PakBuilder::new();
        var builder = PakBuilder.new()
        // Rust: if let Some(aes_key) = aes_key { builder = builder.key(aes_key); }
        if (aes_key != null) {
            builder = builder.key(aes_key.key)
        }
        // Rust: let pak = builder.reader(&mut BufReader::new(File::open(&action.input)?))?;
        val pak: com.github.jpabscale.zenpak4j.repak.PakReader
        FileChannel.open(Paths.get(action.input), StandardOpenOption.READ).use { ch ->
            pak = builder.reader(ch, current_context())
        }
        // Need fresh builder for subsequent reads? Pak is now loaded; reuse pak
        // Rust: let mount_point = PathBuf::from(pak.mount_point());
        val mount_point = Paths.get(pak.mount_point())
        // Rust: let prefix = Path::new(&action.strip_prefix);
        val prefix = Paths.get(action.strip_prefix)
        // Rust: let full_paths = pak.files().into_iter().map(|f| (mount_point.join(&f), f)).collect::<Vec<_>>();
        val full_paths = pak.files().map { f -> Pair(mount_point.resolve(f), f) }
        // Rust: let stripped = full_paths.iter().map(|(full_path, _path)| full_path.strip_prefix(prefix).map_err(|_| PrefixMismatch { path: full_path.to_string_lossy().to_string(), prefix: prefix.to_string_lossy().to_string(), })).collect::<Result<Vec<_>, _>>()?;
        val stripped = full_paths.map { (full_path, _) ->
            try {
                strip_prefix(full_path, prefix)
            } catch (ex: RepakError.PrefixMismatch) {
                throw RepakError.PrefixMismatch(prefix = action.strip_prefix, path = full_path.toString())
            }
        }
        // Rust: let hashes: Arc<Mutex<BTreeMap<Cow<str>, Vec<u8>>>> = Default::default();
        val hashes = TreeMap<String, ByteArray>()
        val lock = Any()
        // Rust: full_paths.par_iter().zip(stripped).try_for_each_init(|| (hashes.clone(), File::open(&action.input)), |(hashes, file), ((_full_path, path), stripped)| { let mut hasher = Sha256::new(); pak.read_file(path, &mut BufReader::new(file.as_ref().unwrap()), &mut hasher)?; let hash = hasher.finalize(); hashes.lock().unwrap().insert(stripped.to_slash_lossy(), hash.to_vec()); Ok(()) })?;
        // Kotlin: parallel via parallelStream, per-thread FileChannel
        val pairs = full_paths.zip(stripped)
        try {
            pairs.parallelStream().forEach { (pair, strippedPath) ->
                val (_, entryPath) = pair
                val digest = MessageDigest.getInstance("SHA-256")
                val out = object : OutputStream() {
                    override fun write(b: Int) {
                        digest.update(b.toByte())
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        digest.update(b, off, len)
                    }
                }
                try {
                    FileChannel.open(Paths.get(action.input), StandardOpenOption.READ).use { ch ->
                        pak.read_file(entryPath, ch, out)
                    }
                } catch (ex: Exception) {
                    throw RuntimeException(ex)
                }
                val hash = digest.digest()
                val slash = to_slash_lossy(strippedPath)
                synchronized(lock) {
                    hashes[slash] = hash
                }
            }
        } catch (e: RuntimeException) {
            val cause = e.cause ?: e
            if (cause is RepakError) throw cause
            // unwrap if cause is runtime wrapping repak error
            var curr: Throwable? = cause
            while (curr != null) {
                if (curr is RepakError) throw curr
                curr = curr.cause
            }
            throw e
        }
        // Rust: for (file, hash) in hashes.lock().unwrap().iter() { println!("{} {}", hex::encode(hash), file); }
        for ((file, hash) in hashes) {
            println("${hex_encode(hash)} $file")
        }
}

// Rust: repak_cli/src/main.rs:327 fn unpack
fun unpack(aes_key: AesKey?, action: ActionUnpack) {
        //@parity:on EXC-006
        // Rust: let inputs = action.input.iter().flat_map(|input| std::env::split_paths(OsStr::new(input))).collect::<Vec<_>>();
        val inputs = action.input.flatMap { split_paths(it) }
        // Rust: if inputs.is_empty() { return Ok(()); }
        if (inputs.isEmpty()) {
            return
        }
        // Rust: let output = action.output.as_ref().map(PathBuf::from).unwrap_or_else(|| inputs[0].with_extension(""));
        val output: Path = if (action.output != null) {
            Paths.get(action.output)
        } else {
            val first = Paths.get(inputs[0])
            val fileName = first.fileName?.toString() ?: first.toString()
            val withoutExt = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
            val sibling = first.parent?.resolve(withoutExt) ?: Paths.get(withoutExt)
            sibling
        }
        //@parity:off EXC-006
        // Rust: match fs::create_dir(&output) { Ok(_) => Ok(()), Err(ref e) if action.output.is_some() && e.kind() == AlreadyExists => Ok(()), Err(e) => Err(e) }?;
        try {
            Files.createDirectory(output)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            if (action.output != null) {
                // Ok
            } else {
                throw e
            }
        } catch (e: java.io.IOException) {
            // For other IO, propagate as RepakError.Other or Io
            throw e
        }
        // Rust: if action.output.is_none() && !action.force && output.read_dir()?.next().is_some() { return Err(OutputNotEmpty(output.to_string_lossy().to_string())) }
        if (action.output == null && !action.force) {
            Files.list(output).use { stream ->
                if (stream.findFirst().isPresent) {
                    throw RepakError.OutputNotEmpty(output.toString())
                }
            }
        }
        // Rust: struct UnpackEntry { entry_path, out_path, out_dir }
        data class UnpackEntry(val entry_path: String, val out_path: Path, val out_dir: Path)
        // Rust: let prefix = Path::new(&action.strip_prefix);
        val prefix = Paths.get(action.strip_prefix)
        //@parity:on EXC-006
        // Rust: let mut selected_paths = HashSet::new();
        val selected_paths = HashSet<Path>()
        // Rust: let mut entries_by_input = Vec::with_capacity(inputs.len());
        val entries_by_input = mutableListOf<List<UnpackEntry>>()
        // Rust: for input in &inputs { let mut builder = PakBuilder::new(); if let Some(aes_key) = aes_key.clone() { builder = builder.key(aes_key); } let pak = builder.reader(&mut BufReader::new(File::open(input)?))?; ... }
        for (inputStr in inputs) {
            var builder = PakBuilder.new()
            if (aes_key != null) {
                builder = builder.key(aes_key.key)
            }
            val pak: com.github.jpabscale.zenpak4j.repak.PakReader
            FileChannel.open(Paths.get(inputStr), StandardOpenOption.READ).use { ch ->
                pak = builder.reader(ch, current_context())
            }
            val mount_point = Paths.get(pak.mount_point())
            val entries = pak.files().mapNotNull { entry_path ->
                val full_path = mount_point.resolve(entry_path).normalize()
                // include filtering
                if (action.include.isNotEmpty()) {
                    val strippedOpt = try {
                        strip_prefix(full_path, prefix)
                    } catch (ex: RepakError.PrefixMismatch) {
                        null
                    }
                    if (strippedOpt == null) {
                        return@mapNotNull null
                    }
                    if (!matches_include(strippedOpt, action.include)) {
                        return@mapNotNull null
                    }
                }
                val stripped: Path = try {
                    strip_prefix(full_path, prefix)
                } catch (ex: RepakError.PrefixMismatch) {
                    throw RepakError.PrefixMismatch(prefix = action.strip_prefix, path = full_path.toString())
                }
                val out_path = clean(output.resolve(stripped))
                if (!out_path.normalize().startsWith(output.normalize())) {
                    throw RepakError.WriteOutsideOutput(out_path.toString())
                }
                if (!selected_paths.add(out_path.normalize())) {
                    return@mapNotNull null
                }
                val out_dir = out_path.parent ?: output
                UnpackEntry(entry_path = entry_path, out_path = out_path, out_dir = out_dir)
            }
            entries_by_input.add(entries)
        }
        //@parity:off EXC-006
        // Rust: let total_entries = entries_by_input.iter().map(Vec::len).sum::<usize>();
        val total_entries = entries_by_input.sumOf { it.size }
        // Rust: let progress = (!action.quiet).then(|| ProgressBar::new(total_entries as u64).with_style(ProgressStyle::with_template(STYLE).unwrap()));
        // Rust: let log = match &progress { Some(progress) => Output::Progress(progress.clone()), None => Output::Stdout };
        val log: Output = Output.Stdout
        // progress ignored; we keep log as Stdout for parity
        // Rust: for (input, entries) in inputs.iter().zip(entries_by_input) { if entries.is_empty() { continue; } let mut builder = PakBuilder::new(); if let Some(aes_key) = aes_key.clone() { builder = builder.key(aes_key); } let mut pak_file = BufReader::new(File::open(input)?); let pak = builder.reader(&mut pak_file)?; entries.par_iter().try_for_each_init(|| File::open(input), |file, entry| { if action.verbose { log.println(format!("unpacking {}", entry.entry_path)) } fs::create_dir_all(&entry.out_dir)?; pak.read_file(&entry.entry_path, &mut BufReader::new(file.as_ref().map_err(|e| RepakError::Other(format!("error reading pak: {e}")))?), &mut fs::File::create(&entry.out_path)?)?; if let Some(progress) = &progress { progress.inc(1); } Ok(()) })?; }
        for ((inputStr, entries) in inputs.zip(entries_by_input)) {
            if (entries.isEmpty()) continue
            var builder = PakBuilder.new()
            if (aes_key != null) {
                builder = builder.key(aes_key.key)
            }
            val pak: com.github.jpabscale.zenpak4j.repak.PakReader
            FileChannel.open(Paths.get(inputStr), StandardOpenOption.READ).use { ch ->
                pak = builder.reader(ch, current_context())
            }
            // parallel unpack
            try {
                entries.parallelStream().forEach { entry ->
                    if (action.verbose) {
                        log.println("unpacking ${entry.entry_path}")
                    }
                    try {
                        Files.createDirectories(entry.out_dir)
                    } catch (ex: Exception) {
                        throw RuntimeException(ex)
                    }
                    try {
                        FileChannel.open(Paths.get(inputStr), StandardOpenOption.READ).use { readChannel ->
                            Files.newOutputStream(entry.out_path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { outStream ->
                                pak.read_file(entry.entry_path, readChannel, outStream)
                            }
                        }
                    } catch (ex: Exception) {
                        throw RuntimeException(ex)
                    }
                }
            } catch (e: RuntimeException) {
                var cause: Throwable? = e.cause ?: e
                while (cause != null) {
                    if (cause is RepakError) throw cause
                    if (cause is java.io.IOException) throw RepakError.Other(cause.message ?: cause.toString())
                    cause = cause.cause
                }
                // fallback
                throw RepakError.Other(e.message ?: e.toString())
            }
        }
        // Rust: if let Some(progress) = progress { progress.finish(); }
        // Rust: if !action.quiet { println!("Unpacked {} files to {} from {} inputs", total_entries, output.display(), inputs.len()); }
        //@parity:on EXC-006
        if (!action.quiet) {
            println("Unpacked $total_entries files to $output from ${inputs.size} inputs")
        }
        //@parity:off EXC-006
}

// Rust: repak_cli/src/main.rs:492 fn pack
fun pack(action: ActionPack) {
        // Rust: let output = args.output.map(PathBuf::from).unwrap_or_else(|| PathBuf::from(format!("{}.pak", args.input)));
        val output: Path = if (action.output != null) {
            Paths.get(action.output)
        } else {
            Paths.get("${action.input}.pak")
        }
        // Rust: fn collect_files(paths: &mut Vec<PathBuf>, dir: &Path) -> io::Result<()> { for entry in fs::read_dir(dir)? { let entry = entry?; let path = entry.path(); if path.is_dir() { collect_files(paths, &path)?; } else { paths.push(entry.path()); } } Ok(()) }
        fun collect_files(paths: MutableList<Path>, dir: Path) {
            Files.list(dir).use { stream ->
                stream.forEach { entry ->
                    if (Files.isDirectory(entry)) {
                        collect_files(paths, entry)
                    } else {
                        paths.add(entry)
                    }
                }
            }
        }
        // Rust: let input_path = Path::new(&args.input);
        val input_path = Paths.get(action.input)
        // Rust: if !input_path.is_dir() { return Err(InputNotADirectory(input_path.to_string_lossy().to_string())) }
        if (!Files.isDirectory(input_path)) {
            throw RepakError.InputNotADirectory(input_path.toString())
        }
        // Rust: let mut paths = vec![]; collect_files(&mut paths, input_path)?; paths.sort();
        val paths = mutableListOf<Path>()
        collect_files(paths, input_path)
        paths.sortBy { it.toString() }
        // Rust: let mut pak = repak::PakBuilder::new().compression(args.compression.iter().cloned()).writer(BufWriter::new(File::create(&output)?), args.version, args.mount_point, Some(args.path_hash_seed));
        var builder = PakBuilder.new()
        val compList = action.compression?.let { listOf(it) } ?: emptyList()
        builder = builder.compression(compList)
        val channel = FileChannel.open(output, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            val writer = builder.writer(channel, action.version, action.mount_point, action.path_hash_seed, current_context())
            // Rust: use indicatif::ProgressIterator; let iter = paths.iter(); let (log, iter) = if !args.quiet { let iter = iter.progress_with_style(ProgressStyle::with_template(STYLE).unwrap()); (Output::Progress(iter.progress.clone()), Either::Left(iter)) } else { (Output::Stdout, Either::Right(iter)) };
            val log: Output = Output.Stdout
            // Rust: let mut result = None; let result_ref = &mut result; rayon::in_place_scope(|scope| -> Result<(), repak::Error> { let (tx, rx) = sync_channel(0); let entry_builder = pak.entry_builder(); scope.spawn(move |_| { *result_ref = Some(iter.par_bridge().try_for_each(|p| { let rel = &p.strip_prefix(input_path).expect("file not in input directory").to_slash().expect("failed to convert to slash path"); if args.verbose { log.println(format!("packing {}", &rel)) } let entry = entry_builder.build_entry(true, std::fs::read(p)?)?; tx.send((rel.to_string(), entry)).unwrap(); Ok(()) })); }); for (path, entry) in rx { pak.write_entry(path, entry)?; } Ok(()) })?; result.unwrap()?;
            // Kotlin: Channel(0) rendezvous + parallel compress via coroutineScope
            val entry_builder = writer.entry_builder()
            //@parity:on EXC-004
            // Deterministic: collect all entries via parallel compress, sort by path, then write in sorted order
            // Rust uses sync_channel(0) completion order (non-deterministic); Kotlin sorts for byte-identical containers
            val results = java.util.Collections.synchronizedList(mutableListOf<Pair<String, com.github.jpabscale.zenpak4j.repak.PartialEntry<ByteArray>>>())
            var parallelError: Throwable? = null
            try {
                paths.parallelStream().forEach { p ->
                    try {
                        val rel = try {
                            val r = input_path.relativize(p)
                            to_slash_lossy(r)
                        } catch (e: Exception) {
                            throw RuntimeException(RepakError.Other("file not in input directory"))
                        }
                        if (action.verbose) {
                            synchronized(log) { log.println("packing $rel") }
                        }
                        val data = Files.readAllBytes(p)
                        val entry = entry_builder.build_entry(true, data)
                        results.add(Pair(rel, entry))
                    } catch (e: Throwable) {
                        synchronized(results) {
                            if (parallelError == null) parallelError = e
                        }
                    }
                }
                if (parallelError != null) {
                    var cause: Throwable? = parallelError
                    while (cause is RuntimeException && cause.cause != null) cause = cause.cause
                    if (cause is RepakError) throw cause
                    if (cause is Exception) throw RepakError.Other(cause.message ?: cause.toString())
                    throw RepakError.Other(parallelError.message ?: "unknown pack error")
                }
                results.sortBy { it.first }
                for ((rel, entry) in results) {
                    writer.write_entry(rel, entry)
                }
            } catch (e: Exception) {
                throw e
            }
            //@parity:off EXC-004
            // Rust: pak.write_index()?;
            writer.write_index()
        } finally {
            channel.close()
        }
        // Rust: if !args.quiet { println!("Packed {} files to {}", paths.len(), output.display()); }
        if (!action.quiet) {
            println("Packed ${paths.size} files to $output")
        }
}

// Rust: repak_cli/src/main.rs:587 fn get
fun get(aes_key: AesKey?, action: ActionGet) {
        // Rust: let mut reader = BufReader::new(File::open(&args.input)?);
        // Rust: let mut builder = repak::PakBuilder::new(); if let Some(aes_key) = aes_key { builder = builder.key(aes_key); }
        var builder = PakBuilder.new()
        if (aes_key != null) {
            builder = builder.key(aes_key.key)
        }
        FileChannel.open(Paths.get(action.input), StandardOpenOption.READ).use { reader ->
            // Rust: let pak = builder.reader(&mut reader)?;
            val pak = builder.reader(reader, current_context())
            // Rust: let mount_point = PathBuf::from(pak.mount_point());
            val mount_point = Paths.get(pak.mount_point())
            // Rust: let prefix = Path::new(&args.strip_prefix);
            val prefix = Paths.get(action.strip_prefix)
            // Rust: let full_path = prefix.join(args.file);
            val full_path = prefix.resolve(action.file).normalize()
            // Rust: let file = full_path.strip_prefix(&mount_point).map_err(|_| PrefixMismatch { path: full_path.to_string_lossy().to_string(), prefix: mount_point.to_string_lossy().to_string() })?;
            val file = try {
                strip_prefix(full_path, mount_point)
            } catch (ex: RepakError.PrefixMismatch) {
                throw RepakError.PrefixMismatch(prefix = mount_point.toString(), path = full_path.toString())
            }
            // Rust: std::io::stdout().write_all(&pak.get(&file.to_slash_lossy(), &mut reader)?)?;
            val slash = to_slash_lossy(file)
            val data = pak.get(slash, reader)
            System.out.write(data)
            System.out.flush()
        }
}
//@parity:off EXC-014
