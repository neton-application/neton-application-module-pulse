package table

import model.PulseApp
import model.PulseAppTableImpl
import model.PulseAppVersion
import model.PulseAppVersionTableImpl
import neton.database.api.Table

object PulseAppTable : Table<PulseApp, Long> by PulseAppTableImpl

object PulseAppVersionTable : Table<PulseAppVersion, Long> by PulseAppVersionTableImpl
