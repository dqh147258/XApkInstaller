package com.yxf.xapkinstallsimple

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import com.yxf.rxandroidextensions.rxRequestInstallPackagesPermission
import com.yxf.rxandroidextensions.rxRequestSinglePermission
import com.yxf.xapkinstaller.XApkInstaller
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rxRequestSinglePermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .subscribe {
                if (it) {
                    rxRequestInstallPackagesPermission()
                        .subscribe { result ->
                            if (result) {
                                install()
                            }
                        }
                }
            }
    }

    private fun install() {
        XApkInstaller(
            "${Environment.getExternalStorageDirectory()}/Download/jp.co.taito.groovecoasterzero.xapk",
            this
        )
            .install()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                Log.d("Debug", "$it")
            }, {
                it.printStackTrace()
            })
    }
}