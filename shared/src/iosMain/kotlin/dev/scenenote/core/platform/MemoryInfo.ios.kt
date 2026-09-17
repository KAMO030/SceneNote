package dev.scenenote.core.platform

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.darwin.TASK_VM_INFO
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import platform.Foundation.NSProcessInfo

actual object MemoryInfo {
    /** phys_footprint：Xcode 内存表里的那个数。 */
    actual fun residentBytes(): Long = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<IntVar>()).toUInt()
        val kr = task_info(mach_task_self_, TASK_VM_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
        if (kr == 0) info.phys_footprint.toLong() else -1L
    }

    actual fun totalBytes(): Long = NSProcessInfo.processInfo.physicalMemory.toLong()
}
