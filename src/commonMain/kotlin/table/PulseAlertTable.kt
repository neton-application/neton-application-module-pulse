package table

import model.PulseAlertRecord
import model.PulseAlertRecordTableImpl
import model.PulseAlertRule
import model.PulseAlertRuleTableImpl
import neton.database.api.Table

object PulseAlertRuleTable : Table<PulseAlertRule, Long> by PulseAlertRuleTableImpl
object PulseAlertRecordTable : Table<PulseAlertRecord, Long> by PulseAlertRecordTableImpl
