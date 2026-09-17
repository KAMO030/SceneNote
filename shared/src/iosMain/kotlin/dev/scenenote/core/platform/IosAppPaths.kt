package dev.scenenote.core.platform

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

class IosAppPaths : AppPaths {
    private val fm = NSFileManager.defaultManager
    override val filesDir: String = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String) ?: "/tmp"
    override val cacheDir: String = (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String) ?: "/tmp"

    override fun ensureDir(path: String) { memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        fm.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = err.ptr)
    } }
    override fun exists(path: String): Boolean = fm.fileExistsAtPath(path)
    override fun sizeBytes(path: String): Long = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        (fm.attributesOfItemAtPath(path, err.ptr)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
    }
    override fun listFiles(dir: String): List<String> = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        (fm.contentsOfDirectoryAtPath(dir, err.ptr)?.filterIsInstance<String>() ?: emptyList()).map { "$dir/$it" }.sorted()
    }
    override fun delete(path: String): Boolean = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        fm.removeItemAtPath(path, err.ptr)
    }
    override fun readText(path: String): String? = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = err.ptr)
    }
    override fun appendText(path: String, text: String) {
        val merged = (readText(path) ?: "") + text
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            NSString.create(string = merged).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = err.ptr)
        }
    }
}
