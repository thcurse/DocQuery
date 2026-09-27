using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace DocQuery {
    // Windows Job limits cover native allocations as well as the V8 heap.
    public static class DevMemoryGuard {
        [StructLayout(LayoutKind.Sequential)] struct BasicLimit {
            public long ProcessTime, JobTime;
            public uint Flags;
            public UIntPtr MinWorkingSet, MaxWorkingSet;
            public uint ActiveProcesses;
            public UIntPtr Affinity;
            public uint Priority, Scheduling;
        }
        [StructLayout(LayoutKind.Sequential)] struct IoCounters {
            public ulong ReadCount, WriteCount, OtherCount, ReadBytes, WriteBytes, OtherBytes;
        }
        [StructLayout(LayoutKind.Sequential)] struct ExtendedLimit {
            public BasicLimit Basic;
            public IoCounters Io;
            public UIntPtr ProcessMemory, JobMemory, PeakProcess, PeakJob;
        }
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct StartupInfo {
            public uint Size;
            public string Reserved, Desktop, Title;
            public uint X, Y, XSize, YSize, XChars, YChars, Fill, Flags;
            public ushort Show, ReservedSize;
            public IntPtr ReservedData, Input, Output, Error;
        }
        [StructLayout(LayoutKind.Sequential)] struct ProcessInfo {
            public IntPtr Process, Thread;
            public uint ProcessId, ThreadId;
        }
        [StructLayout(LayoutKind.Sequential)] struct MemoryStatus {
            public uint Length, Load;
            public ulong TotalPhysical, FreePhysical, TotalCommit, FreeCommit, TotalVirtual, FreeVirtual, FreeExtended;
        }
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern IntPtr CreateJobObject(IntPtr attributes, string name);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetInformationJobObject(IntPtr job, int type, ref ExtendedLimit data, uint size);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool CreateProcess(string application, StringBuilder command, IntPtr processAttributes, IntPtr threadAttributes, bool inherit, uint flags, IntPtr environment, string directory, ref StartupInfo startup, out ProcessInfo process);
        [DllImport("kernel32.dll", SetLastError = true)] static extern uint ResumeThread(IntPtr thread);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool GetExitCodeProcess(IntPtr process, out uint code);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
        [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr process, uint code);
        [DllImport("kernel32.dll")] static extern bool TerminateJobObject(IntPtr job, uint code);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool GlobalMemoryStatusEx(ref MemoryStatus status);
        [DllImport("kernel32.dll")] static extern IntPtr GetStdHandle(int kind);

        // Windows command-line quoting; no shell is used for the child process.
        static string Quote(string value) {
            var output = new StringBuilder("\"");
            int slashes = 0;
            foreach (char character in value) {
                if (character == '\\') { slashes++; continue; }
                output.Append('\\', character == '"' ? slashes * 2 + 1 : slashes);
                output.Append(character);
                slashes = 0;
            }
            output.Append('\\', slashes * 2);
            return output.Append('"').ToString();
        }
        static MemoryStatus Memory() {
            var state = new MemoryStatus();
            state.Length = (uint)Marshal.SizeOf(state);
            if (!GlobalMemoryStatusEx(ref state)) throw new Win32Exception(Marshal.GetLastWin32Error());
            return state;
        }
        static void Log(string path, string action, int pid, long used, ulong available) {
            if (File.Exists(path) && new FileInfo(path).Length > 256 * 1024) {
                if (File.Exists(path + ".previous")) File.Delete(path + ".previous");
                File.Move(path, path + ".previous");
            }
            File.AppendAllText(path, "{\"time\":\"" + DateTime.UtcNow.ToString("o") + "\",\"event\":\"" + action +
                "\",\"pid\":" + pid + ",\"privateBytes\":" + used + ",\"freeCommitBytes\":" + available + "}\n");
        }
        public static int Run(string node, string entry, string[] args, string directory, int processMiB, int heapMiB, int parentId) {
            if (IntPtr.Size != 8) throw new InvalidOperationException("64-bit PowerShell is required.");
            const ulong MiB = 1024 * 1024;
            const ulong reserve = 2048 * MiB;
            if (Memory().FreeCommit < reserve) throw new InvalidOperationException("Less than 2 GiB of system commit is available; server not started.");
            IntPtr job = CreateJobObject(IntPtr.Zero, null);
            if (job == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            var info = new ProcessInfo();
            bool assigned = false;
            Process child = null, parent = null;
            try {
                var limits = new ExtendedLimit();
                // PROCESS_MEMORY | JOB_MEMORY | KILL_ON_JOB_CLOSE; no breakaway or inheritable job handle.
                limits.Basic.Flags = 0x100 | 0x200 | 0x2000;
                limits.ProcessMemory = new UIntPtr((ulong)processMiB * MiB);
                limits.JobMemory = new UIntPtr(2048 * MiB);
                if (!SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf(limits))) throw new Win32Exception(Marshal.GetLastWin32Error());
                if (parentId != 0) parent = Process.GetProcessById(parentId);
                var command = new StringBuilder(Quote(node) + " --max-old-space-size=" + heapMiB + " " + Quote(entry));
                foreach (string argument in args) command.Append(" " + Quote(argument));
                var startup = new StartupInfo();
                startup.Size = (uint)Marshal.SizeOf(startup);
                startup.Flags = 0x100; // STARTF_USESTDHANDLES
                startup.Input = GetStdHandle(-10); startup.Output = GetStdHandle(-11); startup.Error = GetStdHandle(-12);
                // Start suspended: the worker cannot allocate or spawn children before limits are installed.
                if (!CreateProcess(node, command, IntPtr.Zero, IntPtr.Zero, true, 0x4, IntPtr.Zero, directory, ref startup, out info)) throw new Win32Exception(Marshal.GetLastWin32Error());
                if (!AssignProcessToJobObject(job, info.Process)) throw new Win32Exception(Marshal.GetLastWin32Error());
                assigned = true;
                child = Process.GetProcessById((int)info.ProcessId);
                string logDirectory = Path.Combine(directory, "node_modules", ".cache", "docquery-memory");
                Directory.CreateDirectory(logDirectory);
                string log = Path.Combine(logDirectory, info.ProcessId + "-windows.jsonl");
                Log(log, "start", child.Id, 0, Memory().FreeCommit);
                Console.Error.WriteLine("[DocQuery dev] Protected PID {0}; process <= {1} MiB, group <= 2048 MiB; logs: {2}", child.Id, processMiB, logDirectory);
                if (ResumeThread(info.Thread) == uint.MaxValue) throw new Win32Exception(Marshal.GetLastWin32Error());
                int ticks = 0;
                while (!child.WaitForExit(500)) {
                    child.Refresh();
                    var state = Memory();
                    long used = child.PrivateMemorySize64;
                    string reason = null;
                    if (parent != null && parent.HasExited) reason = "parent-exit";
                    else if (state.FreeCommit < reserve) reason = "system-memory-low";
                    else if ((ulong)used > (ulong)processMiB * MiB * 4 / 5) reason = "process-memory-high";
                    if (reason != null) {
                        Log(log, reason, child.Id, used, state.FreeCommit);
                        Console.Error.WriteLine("[DocQuery dev] Stopping protected server: {0}. No automatic restart. See memory logs.", reason);
                        TerminateJobObject(job, 137);
                        child.WaitForExit(5000);
                        return 137;
                    }
                    if (ticks++ % 20 == 0) Log(log, "sample", child.Id, used, state.FreeCommit);
                }
                Log(log, "exit", child.Id, 0, Memory().FreeCommit);
                uint exitCode;
                if (!GetExitCodeProcess(info.Process, out exitCode)) throw new Win32Exception(Marshal.GetLastWin32Error());
                return unchecked((int)exitCode);
            } finally {
                // Even assignment failure must dispose of the still-suspended process.
                if (!assigned && info.Process != IntPtr.Zero) TerminateProcess(info.Process, 1);
                CloseHandle(job);
                if (info.Thread != IntPtr.Zero) CloseHandle(info.Thread);
                if (info.Process != IntPtr.Zero) CloseHandle(info.Process);
                if (child != null) child.Dispose();
                if (parent != null) parent.Dispose();
            }
        }
    }
}
