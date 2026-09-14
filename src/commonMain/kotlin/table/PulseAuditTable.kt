package table

import model.PulseModuleAlert
import model.PulseModuleAlertTableImpl
import model.PulseModuleRule
import model.PulseModuleRuleTableImpl
import model.PulseModuleSighting
import model.PulseModuleSightingTableImpl
import neton.database.api.Table

object PulseModuleRuleTable : Table<PulseModuleRule, Long> by PulseModuleRuleTableImpl
object PulseModuleAlertTable : Table<PulseModuleAlert, Long> by PulseModuleAlertTableImpl
object PulseModuleSightingTable : Table<PulseModuleSighting, Long> by PulseModuleSightingTableImpl
