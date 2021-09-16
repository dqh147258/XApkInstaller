package com.yxf.xapkinstaller.bean

import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName


internal data class XSplitApks(
        @Expose
        @SerializedName("file")
        var fileName: String,
        @Expose
        @SerializedName("id")
        var _id: String
)