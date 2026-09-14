package table

import model.PulseCrashIssue
import model.PulseCrashIssueTableImpl
import model.PulseCrashSighting
import model.PulseCrashSightingTableImpl
import model.PulseSymbol
import model.PulseSymbolTableImpl
import neton.database.api.Table

object PulseCrashIssueTable : Table<PulseCrashIssue, Long> by PulseCrashIssueTableImpl
object PulseCrashSightingTable : Table<PulseCrashSighting, Long> by PulseCrashSightingTableImpl
object PulseSymbolTable : Table<PulseSymbol, Long> by PulseSymbolTableImpl
