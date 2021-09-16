package com.yxf.xapkinstaller

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import com.yxf.xapkinstaller.bean.XApkManifest
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.*

class XApkInstaller(private val path: String, sourceContext: Context) {


    companion object {

        const val STATE_START = 0
        const val STATE_INSTALLING = 1
        const val STATE_UNZIP_OBB = 2
        const val STATE_UNZIP_APK = 3
        const val STATE_INSTALL_APK = 4
        const val STATE_INSTALL_FINISHED = 5


        //这个XApk正在安装中,不能重复安装
        const val ERROR_IN_INSTALLING = 1

        const val ERROR_FILE_NOT_EXIST = 2
        const val ERROR_NOT_ZIP_FILE = 3
        const val ERROR_OPERATE_SYSTEM_NOT_SUPPORT = 4
        const val ERROR_OBB_UNZIP_FAILED = 5
        const val ERROR_APK_UNZIP_FAILED = 6


        private val TAG = XApkInstaller::class.java.simpleName

    }

    private val context = sourceContext.applicationContext

    private val xApkFile: File by lazy { File(path) }

    private val tempFolder by lazy {
        File(getExternalDirectory(), "xapk_installer_temp").also {
            if (!it.exists()) {
                it.mkdirs()
            }
        }
    }

    private var hasInstalledOrInInstalling = false


    fun install(): Observable<XApkInstallResult> {
        if (hasInstalledOrInInstalling) {
            return Observable.error(
                XApkInstallException(
                    ERROR_IN_INSTALLING,
                    "the xapk is in installing"
                )
            )
        }
        hasInstalledOrInInstalling = true

        return Observable.create {
            it.onNext(XApkInstallResult(STATE_START))
            it.onNext(XApkInstallResult(STATE_INSTALLING))

            if (!xApkFile.exists()) {
                it.onError(
                    XApkInstallException(
                        ERROR_FILE_NOT_EXIST,
                        "xapk file not exist in ${xApkFile.path}"
                    )
                )
                return@create
            }
            val zipFile = parseXApkZipFile(xApkFile)
            if (zipFile == null) {
                it.onError(XApkInstallException(ERROR_NOT_ZIP_FILE, "open zip file failed"))
                return@create
            }
            if (!clearTempFolder()) {
                Log.w(TAG, "clear temp folder failed")
            }
            val manifest = getXApkManifest(zipFile)
            if (manifest != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && manifest.XSplitApks?.isEmpty() == false) {
                    it.onError(
                        XApkInstallException(
                            ERROR_OPERATE_SYSTEM_NOT_SUPPORT,
                            "operate system not support xapk file with multi xapk"
                        )
                    )
                    return@create
                }
                if (manifest.useObbs()) {
                    if (!installXApkObb(zipFile, manifest, it)) {
                        return@create
                    }
                }
                if (manifest.useSplitApks()) {
                    if (!installSplitApks(xApkFile, zipFile, manifest, it)) {
                        return@create
                    }
                } else {
                    if (!installApk(zipFile, it, manifest)) {
                        return@create
                    }
                }
            } else {
                if (!installXApkObb(zipFile, it)) {
                    return@create
                }
                if (isSingleApk(zipFile)) {
                    if (!installApk(zipFile, it)) {
                        return@create
                    }
                } else {
                    it.onError(
                        XApkInstallException(
                            ERROR_APK_UNZIP_FAILED,
                            "not support installing multi-apk without manifest"
                        )
                    )
                    return@create
                }
            }
            it.onNext(XApkInstallResult(STATE_INSTALL_FINISHED, 100))
            it.onComplete()
        }
    }

    private fun installApk(
        zipFile: ZipFile,
        emitter: ObservableEmitter<XApkInstallResult>,
        xApkManifest: XApkManifest? = null
    ): Boolean {
        val apkFileName =
            xApkManifest?.let { "${it.packageName}.apk" } ?: getXApkApkFileName(zipFile)!!
        var isApkSuccess = false
        val tempApk = File(tempFolder, apkFileName)
        val totalLength =
            xApkManifest?.let { getXApkTotalSize(zipFile, it) } ?: getXApkApkTotalSize(zipFile)
        getZipFileInputStream(zipFile, apkFileName)?.let { input ->
            val data = ByteArray(1024 * 16)
            var len = 0
            var currentOffset = 0L
            var lastPercent = -1L
            BufferedOutputStream(FileOutputStream(tempApk)).use { writer ->
                BufferedInputStream(input).use { reader ->
                    while (reader.read(data).also { len = it } != -1) {
                        writer.write(data, 0, len)
                        currentOffset += len
                        val percent = currentOffset * 100 / totalLength
                        if (percent > lastPercent) {
                            lastPercent = percent
                            emitter.onNext(XApkInstallResult(STATE_UNZIP_APK, percent.toInt()))
                        }
                    }
                }
            }
            isApkSuccess = true
        }
        if (isApkSuccess) {
            emitter.onNext(XApkInstallResult(STATE_INSTALL_APK, 100))
            startActivityForInstallApk(tempApk.absolutePath)
        } else {
            emitter.onError(XApkInstallException(ERROR_APK_UNZIP_FAILED, "apk unzip failed"))
        }
        return isApkSuccess
    }


    private fun installSplitApks(
        xApkFile: File, zipFile: ZipFile, xApkManifest: XApkManifest,
        emitter: ObservableEmitter<XApkInstallResult>
    ): Boolean {
        val fileList = arrayListOf<String>()
        val totalSize = getXApkTotalSize(zipFile, xApkManifest)
        var hasReadSize = 0L
        xApkManifest.XSplitApks!!.forEach {
            var currentOffset = 0L
            getZipFileInputStream(zipFile, it.fileName)?.let { input ->
                val tempApk = File(
                    getXApkInstallTempFolder(xApkManifest.packageName),
                    it.fileName
                )
                val data = ByteArray(1024 * 16)
                var len = 0
                var lastPercent = -1L
                BufferedOutputStream(FileOutputStream(tempApk)).use { writer ->
                    BufferedInputStream(input).use { reader ->
                        while (reader.read(data).also { len = it } != -1) {
                            writer.write(data, 0, len)
                            currentOffset += len
                            val percent = (currentOffset + hasReadSize) * 100 / totalSize
                            if (percent > lastPercent) {
                                lastPercent = percent
                                emitter.onNext(XApkInstallResult(STATE_UNZIP_APK, percent.toInt()))
                            }
                        }
                    }
                }

                hasReadSize += currentOffset
                if (tempApk.exists()) {
                    fileList.add(tempApk.absolutePath)
                }
            }
        }
        if (fileList.isNotEmpty()) {
            emitter.onNext(XApkInstallResult(STATE_INSTALL_APK, 100))
            startActivityForInstallApk(fileList[0])
        } else {
            emitter.onError(XApkInstallException(ERROR_APK_UNZIP_FAILED, "unzip apk failed"))
            return false
        }
        return true
    }

    private fun isFileExist(filePath: String): Boolean {
        return filePath.isNotEmpty() && File(filePath).exists()
    }

    private fun startActivityForInstallApk(filePath: String) {
        if (isFileExist(filePath)) {
            Intent().apply {
                this.action = Intent.ACTION_VIEW
                this.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    this.flags = this.flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                this.setDataAndType(
                    fromFileProvider(File(filePath)),
                    "application/vnd.android.package-archive"
                )
                context.startActivity(this)
            }
        }
    }

    private fun fromFileProvider(file: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.xapk_installer_fileprovider",
                file
            )
        } else {
            Uri.fromFile(file)
        }
    }


    private fun getXApkInstallTempFolder(packageName: String): File {
        val folder = File(tempFolder, packageName)
        return folder
    }

    private fun installXApkObb(
        zipFile: ZipFile,
        emitter: ObservableEmitter<XApkInstallResult>
    ): Boolean {
        var obbSuccess = true
        if (isObbXApk(zipFile)) {
            val obbTotalSize = getXApkObbTotalSize(zipFile)
            val entries = zipFile.entries
            var hasReadSize = 0L
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (!e.isDirectory && e.name.endsWith(".obb")) {
                    obbSuccess = obbSuccess && installSingleXApkObb(
                        zipFile,
                        emitter,
                        obbTotalSize,
                        hasReadSize,
                        e.name,
                        e.name
                    )
                    hasReadSize += zipFile.getEntry(e.name).size
                }
            }
        } else {
            obbSuccess = false
        }
        if (!obbSuccess) {
            emitter.onError(XApkInstallException(ERROR_OBB_UNZIP_FAILED, "obb file write failed"))
        }
        return obbSuccess
    }


    private fun installXApkObb(
        zipFile: ZipFile, xApkManifest: XApkManifest,
        emitter: ObservableEmitter<XApkInstallResult>
    ): Boolean {
        var obbSuccess = true
        if (xApkManifest.useObbs()) {
            val obbTotalSize = getXApkObbTotalSize(zipFile, xApkManifest)
            var hasReadSize = 0L
            for (item in xApkManifest.expansionList!!) {
                obbSuccess = obbSuccess && installSingleXApkObb(
                    zipFile,
                    emitter,
                    obbTotalSize,
                    hasReadSize,
                    item.xFile,
                    item.installPath
                )
                hasReadSize += zipFile.getEntry(item.xFile).size
            }
        } else {
            obbSuccess = false
        }
        if (!obbSuccess) {
            emitter.onError(XApkInstallException(ERROR_OBB_UNZIP_FAILED, "obb file write failed"))
        }
        return obbSuccess
    }

    private fun getStorage(): File? {
        return Environment.getExternalStorageDirectory()
    }

    private fun getExternalDirectory(): File? {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O) {
            return  File( getStorage(),"Android/data/${context.packageName}/files/")
        }

        return context.getExternalFilesDir(null)
    }


    private fun installSingleXApkObb(
        zipFile: ZipFile,
        emitter: ObservableEmitter<XApkInstallResult>,
        obbTotalSize: Long,
        hasReadSize: Long,
        obbFileName: String,
        installPath: String
    ): Boolean {
        val inputStream = getZipFileInputStream(zipFile, obbFileName)!!
        val obbFile = File(getStorage(), installPath)
        if (!obbFile.parentFile.exists()) {
            obbFile.parentFile.mkdirs()
        }
        val data = ByteArray(1024 * 16)
        var len = 0
        var currentOffset = 0L
        var lastPercent = -1L
        BufferedOutputStream(FileOutputStream(obbFile)).use { writer ->
            BufferedInputStream(inputStream).use { reader ->
                while (reader.read(data).also { len = it } != -1) {
                    writer.write(data, 0, len)
                    currentOffset += len
                    val percent = (currentOffset + hasReadSize) * 100 / obbTotalSize
                    if (percent > lastPercent) {
                        lastPercent = percent
                        emitter.onNext(XApkInstallResult(STATE_UNZIP_OBB, percent.toInt()))
                    }
                }
            }
        }
        return true
    }

    private fun getXApkTotalSize(zipFile: ZipFile, xApkManifest: XApkManifest): Long {
        return if (xApkManifest.useSplitApks()) {
            var totalLength = 0L
            xApkManifest.XSplitApks?.forEach {
                totalLength += zipFile.getEntry(it.fileName)?.size ?: 0L
            }
            totalLength
        } else {
            val apkFileName = "${xApkManifest.packageName}.apk"
            zipFile.getEntry(apkFileName).size
        }
    }

    private fun getXApkObbTotalSize(zipFile: ZipFile, xApkManifest: XApkManifest): Long {
        return if (xApkManifest.useObbs()) {
            var totalLength = 0L
            for (item in xApkManifest.expansionList!!) {
                totalLength += zipFile.getEntry(item.xFile)?.size ?: 0L
            }
            totalLength
        } else {
            0L
        }
    }


    @WorkerThread
    private fun getXApkManifest(zipFile: ZipFile): XApkManifest? {
        var xApkManifest: XApkManifest? = null
        getZipFileInputStream(zipFile, "manifest.json")?.let {
            xApkManifest = XApkManifest.fromJson(InputStreamReader(it, "UTF-8"))
        }
        return xApkManifest
    }

    @WorkerThread
    private fun getZipFileInputStream(
        zipFile: ZipFile,
        inputName: String,
        isRaw: Boolean = false
    ): InputStream? {
        var inputStream: InputStream? = null
        try {
            zipFile.getEntry(inputName)?.apply {
                inputStream = if (isRaw) {
                    zipFile.getRawInputStream(this)
                } else {
                    zipFile.getInputStream(this)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return inputStream
    }


    @WorkerThread
    private fun parseXApkZipFile(xApkFile: File): ZipFile? {
        var zipFile: ZipFile? = null
        if (xApkFile != null && xApkFile.exists()) {
            try {
                zipFile = ZipFile(xApkFile)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return zipFile
    }


    private fun isObbXApk(zipFile: ZipFile): Boolean {
        val entries = zipFile.entries
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (!e.isDirectory && e.name.endsWith(".obb")) {
                return true
            }
        }
        return false
    }

    private fun isSingleApk(zipFile: ZipFile): Boolean {
        var result = false
        val entries = zipFile.entries
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (!e.isDirectory && e.name.endsWith(".apk")) {
                if (result) {
                    return false
                } else {
                    result = true
                }
            }
        }
        return result
    }

    private fun getXApkApkFileName(zipFile: ZipFile): String? {
        val entries = zipFile.entries
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            val name = e.name
            if (!e.isDirectory && name.endsWith(".apk")) {
                return name
            }
        }
        return null
    }

    private fun getXApkApkTotalSize(zipFile: ZipFile): Long {
        return getZipFileTotalSize(zipFile, ".apk")
    }


    private fun getXApkObbTotalSize(zipFile: ZipFile): Long {
        return getZipFileTotalSize(zipFile, ".obb")
    }

    private fun getZipFileTotalSize(zipFile: ZipFile, suffix: String): Long {
        var totalLength = 0L
        val entries = zipFile.entries
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (!e.isDirectory && e.name.endsWith(suffix)) {
                totalLength += e.size
            }
        }
        return totalLength
    }

    private fun clearTempFolder(): Boolean {
        return deleteDirectoryAndFile(tempFolder, true)
    }


    private fun deleteDirectoryAndFile(file: File, keepRootDirectory: Boolean = false): Boolean {
        if (!file.exists()) {
            return true
        }
        if (!file.isDirectory) {
            file.delete()
            return true
        }
        var flag = true
        val files = file.listFiles()
        //遍历删除文件夹下的所有文件(包括子目录)
        for (i in files.indices) {
            flag = flag && deleteDirectoryAndFile(files[i])
        }
        if (!keepRootDirectory) {
            flag = flag && file.delete()
        }
        return flag
        //删除当前空目录
    }

}