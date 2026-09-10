package com.mend.app

import android.app.Application
import com.mend.app.data.MendDatabase

class MendApplication : Application() {

    val database: MendDatabase by lazy { MendDatabase.getInstance(this) }
}
