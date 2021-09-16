package com.yxf.xapkinstaller

import java.lang.Exception

class XApkInstallException(val errorCode: Int, message: String) : Exception(message) {


}