package table

import model.PulseEvent
import model.PulseEventTableImpl
import neton.database.api.Table

object PulseEventTable : Table<PulseEvent, Long> by PulseEventTableImpl
