# XApk 安装器
[![](https://www.jitpack.io/v/dqh147258/XApkInstaller.svg)](https://www.jitpack.io/#dqh147258/XApkInstaller)

用于安装XApk格式的安装包

## 使用

## 引用
```groovy
	allprojects {
		repositories {
			//...
			maven { url 'https://www.jitpack.io' }
		}
	}
```

```groovy
	dependencies {
	        implementation 'com.github.dqh147258:XApkInstaller:1.0.+'
	}
```

### 权限申请
需要Sdcard的读写权限

```
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```
AndroidManifest中可以不用处理,XApkInstaller库自带了权限申请,主要是需要运行时申请下权限

在Android10以上是存在一个分区储存的,这里选择是暂时屏蔽它,所以库中带了

```xml
    <application android:requestLegacyExternalStorage="true"/>
```
不过在Android11后对Sdcard的权限限制又更加严格了,我们不能直接访问`Sdcard/Android`目录,这将会导致XApk安装失败

在Android11有两种方法可以解决此问题
- 申请`Sdcard/Android`的访问权限,申请后可以通过URI和DocumentFile类来访问其中的文件,申请这个权限的过程还会调用一个文件夹让用户选择,这种方式较为麻烦暂时不支持这种方式

- 申请未知应用的安装权限,申请未知应用的安装权限后我们就能通过File的方式直接读写`Sdcard/Android/obb`目录

未知应用的安装权限申请流程自行搜索

示例代码
```kotlin
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
```
这里需要注意一点,申请未知应用的安装权限实际上是会导致应用重启的,所以需要做好数据储存和恢复,不然会导致页面异常

### 安装
安装过程十分简单,创建一个XApkInstaller类然后调用其install方法即可

其中XApkInstaller的构造参数是XApk文件路径的路径Context(ApplicationContext即可)
```kotlin
    private fun install() {
        XApkInstaller("${Environment.getExternalStorageDirectory()}/Download/xxx.xapk", this)
            .install()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                Log.d("Debug", "$it")
            }, {
                it.printStackTrace()
            })
    }
```

## 注意
需要注意install只能被调用一次,如果需要重新安装请重新创建XApkInstaller对象

这个安装流程只能到调用系统的PackageInstaller来安装Apk,所以实际上并不是一个完整的流程,最终Apk是否安装成功需要自己监听广播处理



