using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

internal sealed record LaunchRequest(int Protocol, string Workspace, bool Writable, string[] Command,
    string[] ReadRoots, int ParentPid, int TimeoutSeconds);

internal static class Program
{
    private static int Main(string[] args)
    {
        if (args.Length != 1) return 125;
        string requestFile = Path.GetFullPath(args[0]);
        try
        {
            var request = JsonSerializer.Deserialize<LaunchRequest>(File.ReadAllText(requestFile),
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true })
                ?? throw new InvalidOperationException("Missing launch request");
            return Run(request, requestFile);
        }
        catch (Exception error)
        {
            // Only the trusted broker writes this control file; target stdout stays protocol-clean.
            File.WriteAllText(requestFile + ".error", error.Message);
            Console.Error.WriteLine("WINDOWS_SANDBOX_FAILED: " + error.Message);
            return 125;
        }
    }

    private static int Run(LaunchRequest request, string control)
    {
        if (request.Protocol != 1 || request.Command.Length == 0 || request.TimeoutSeconds < 1)
            throw new InvalidOperationException("Invalid sandbox protocol");
        string workspace = CanonicalDirectory(request.Workspace);
        if (Within(control, workspace)) throw new InvalidOperationException("Control file inside workspace");
        CheckTree(workspace);
        string[] readRoots = request.ReadRoots.Select(CanonicalDirectory).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        foreach (string root in readRoots)
        {
            if (Within(workspace, root) || Within(control, root))
                throw new InvalidOperationException("Read grant overlaps protected roots");
            CheckTree(root);
        }
        string application = request.Command[0];
        if (!Path.IsPathFullyQualified(application) || !File.Exists(application))
            throw new InvalidOperationException("Executable must be an existing absolute file");
        CheckAncestors(application);
        IntPtr parent = Win32.OpenProcess(0x100000, false, checked((uint)request.ParentPid));
        Check(parent != IntPtr.Zero, "Open parent process");
        string identity = "DevCLI." + Guid.NewGuid().ToString("N");
        IntPtr sid = IntPtr.Zero, job = IntPtr.Zero, attributes = IntPtr.Zero, environment = IntPtr.Zero;
        var allocations = new List<IntPtr>();
        var handles = new List<IntPtr>();
        var grants = new List<(string Path, FileSystemAccessRule Rule)>();
        Win32.ProcessInformation process = default;
        bool profileCreated = false;
        int result = 125;
        Exception? primaryFailure = null;
        try
        {
            Marshal.ThrowExceptionForHR(Win32.CreateAppContainerProfile(identity, identity, "DevCLI offline task",
                IntPtr.Zero, 0, out sid));
            profileCreated = true;
            var identitySid = new SecurityIdentifier(sid);
            Grant(workspace, identitySid, request.Writable
                ? FileSystemRights.Modify : FileSystemRights.ReadAndExecute, AccessControlType.Allow, grants);
            foreach (string root in readRoots)
                Grant(root, identitySid, FileSystemRights.ReadAndExecute, AccessControlType.Allow, grants);
            string git = Path.Combine(workspace, ".git");
            if (File.Exists(git) || Directory.Exists(git))
                Grant(git, identitySid, FileSystemRights.FullControl, AccessControlType.Deny, grants);

            Marshal.ThrowExceptionForHR(Win32.GetAppContainerFolderPath(identitySid.Value, out IntPtr profilePath));
            string home;
            try { home = Marshal.PtrToStringUni(profilePath) ?? throw new IOException("Missing profile directory"); }
            finally { Marshal.FreeCoTaskMem(profilePath); }
            string temp = Path.Combine(home, "Temp");
            Directory.CreateDirectory(temp);
            string system = Environment.GetFolderPath(Environment.SpecialFolder.System);
            var env = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["SystemRoot"] = Directory.GetParent(system)!.FullName,
                ["WINDIR"] = Directory.GetParent(system)!.FullName,
                ["ComSpec"] = Path.Combine(system, "cmd.exe"),
                ["TEMP"] = temp, ["TMP"] = temp, ["HOME"] = home,
                ["USERPROFILE"] = home, ["LOCALAPPDATA"] = home, ["APPDATA"] = home,
                ["PATH"] = string.Join(";", readRoots.SelectMany(p => new[] { Path.Combine(p, "bin"), p })
                    .Concat(new[] { system, Path.Combine(system, "WindowsPowerShell", "v1.0") })),
                ["PATHEXT"] = ".COM;.EXE;.BAT;.CMD"
            };
            string? java = readRoots.FirstOrDefault(p => File.Exists(Path.Combine(p, "bin", "javac.exe")));
            if (java != null) env["JAVA_HOME"] = java;
            environment = Marshal.StringToHGlobalUni(string.Join('\0', env.Select(p => p.Key + "=" + p.Value)) + "\0\0");

            job = Win32.CreateJobObject(IntPtr.Zero, null);
            Check(job != IntPtr.Zero, "Create job");
            var limits = new Win32.ExtendedLimits
            {
                basic = new Win32.BasicLimits { flags = 0x2000 | 0x8 | 0x200, activeProcesses = 64 },
                jobMemory = (UIntPtr)(1024UL * 1024 * 1024)
            };
            Check(Win32.SetInformationJobObject(job, 9, ref limits, Marshal.SizeOf(limits)), "Set job limits");
            IntPtr size = IntPtr.Zero;
            Win32.InitializeProcThreadAttributeList(IntPtr.Zero, 2, 0, ref size);
            attributes = Marshal.AllocHGlobal(size);
            Check(Win32.InitializeProcThreadAttributeList(attributes, 2, 0, ref size), "Initialize attributes");
            IntPtr capabilities = Allocate(new Win32.SecurityCapabilities { sid = sid }, allocations);
            Check(Win32.UpdateProcThreadAttribute(attributes, 0, (IntPtr)0x20009, capabilities,
                (IntPtr)Marshal.SizeOf<Win32.SecurityCapabilities>(), IntPtr.Zero, IntPtr.Zero), "Set AppContainer");
            foreach (int std in new[] { -10, -11, -12 })
            {
                Check(Win32.DuplicateHandle(Win32.GetCurrentProcess(), Win32.GetStdHandle(std),
                    Win32.GetCurrentProcess(), out IntPtr duplicate, 0, true, 2), "Duplicate stdio");
                handles.Add(duplicate);
            }
            IntPtr inherited = Marshal.AllocHGlobal(IntPtr.Size * handles.Count);
            allocations.Add(inherited);
            Marshal.Copy(handles.ToArray(), 0, inherited, handles.Count);
            Check(Win32.UpdateProcThreadAttribute(attributes, 0, (IntPtr)0x20002, inherited,
                (IntPtr)(IntPtr.Size * handles.Count), IntPtr.Zero, IntPtr.Zero), "Set handle allowlist");
            var startup = new Win32.StartupInfoEx
            {
                attributes = attributes,
                info = new Win32.StartupInfo { cb = Marshal.SizeOf<Win32.StartupInfoEx>(), flags = 0x100,
                    stdin = handles[0], stdout = handles[1], stderr = handles[2] }
            };
            // A .cmd is intentionally invoked through cmd.exe by the trusted Java caller.
            var command = new StringBuilder(string.Join(" ", request.Command.Select(Quote)));
            Check(Win32.CreateProcess(application, command, IntPtr.Zero, IntPtr.Zero, true,
                0x80000 | 0x400 | 0x4 | 0x08000000, environment, workspace, ref startup, out process), "Create isolated process");
            Check(Win32.AssignProcessToJobObject(job, process.process), "Assign job");
            Check(Win32.ResumeThread(process.thread) != uint.MaxValue, "Resume process");
            File.WriteAllText(control + ".ready", "1");
            var watch = Stopwatch.StartNew();
            while (Win32.WaitForSingleObject(process.process, 100) == 258)
            {
                bool timeout = watch.Elapsed.TotalSeconds >= request.TimeoutSeconds;
                if (Win32.WaitForSingleObject(parent, 0) != 258 || File.Exists(control + ".stop") || timeout)
                {
                    if (timeout) File.WriteAllText(control + ".timeout", "timeout");
                    Check(Win32.TerminateJobObject(job, 124), "Terminate job");
                    Check(Win32.WaitForSingleObject(process.process, 5000) == 0, "Wait terminated process");
                    break;
                }
            }
            Check(Win32.GetExitCodeProcess(process.process, out uint exitCode), "Get exit code");
            result = unchecked((int)exitCode);
        }
        catch (Exception e) { primaryFailure = e; throw; }
        finally
        {
            var cleanupErrors = new List<string>();
            // Always quiesce all descendants before returning artifacts to the host.
            if (job != IntPtr.Zero)
            {
                Win32.TerminateJobObject(job, 124);
                var drain = Stopwatch.StartNew();
                while (true)
                {
                    if (!Win32.QueryInformationJobObject(job, 1, out var accounting,
                        Marshal.SizeOf<Win32.Accounting>(), IntPtr.Zero))
                    { cleanupErrors.Add("Cannot confirm job termination"); break; }
                    if (accounting.activeProcesses == 0) break;
                    if (drain.Elapsed.TotalSeconds > 5) { cleanupErrors.Add("Job did not quiesce; artifacts rejected"); break; }
                    Thread.Sleep(20);
                }
                Win32.CloseHandle(job);
            }
            if (process.process != IntPtr.Zero)
            {
                Win32.TerminateProcess(process.process, 124); // Also covers failure before AssignProcessToJobObject.
                Win32.CloseHandle(process.process);
                Win32.CloseHandle(process.thread);
            }
            foreach (IntPtr handle in handles) Win32.CloseHandle(handle);
            if (attributes != IntPtr.Zero) { Win32.DeleteProcThreadAttributeList(attributes); Marshal.FreeHGlobal(attributes); }
            foreach (IntPtr allocation in allocations) Marshal.FreeHGlobal(allocation);
            if (environment != IntPtr.Zero) Marshal.FreeHGlobal(environment);
            bool workspaceSafe = false;
            try { CheckAncestors(workspace); CheckTree(workspace); workspaceSafe = true; }
            catch (Exception e) { cleanupErrors.Add(e.Message); }
            foreach (var grant in Enumerable.Reverse(grants))
            {
                if (!workspaceSafe && Within(grant.Path, workspace)) continue;
                try { EditAcl(grant.Path, grant.Rule, false); }
                catch (Exception e) { cleanupErrors.Add(e.Message); }
            }
            if (profileCreated && Win32.DeleteAppContainerProfile(identity) < 0)
                cleanupErrors.Add("Unable to remove task AppContainer profile " + identity);
            if (sid != IntPtr.Zero) Win32.FreeSid(sid);
            Win32.CloseHandle(parent);
            if (cleanupErrors.Count != 0) throw new IOException((primaryFailure?.Message ?? "")
                + " Sandbox cleanup failed: " + string.Join("; ", cleanupErrors), primaryFailure);
        }
        CheckTree(workspace);
        return result;
    }

    private static IntPtr Allocate<T>(T value, List<IntPtr> allocations) where T : struct
    {
        IntPtr ptr = Marshal.AllocHGlobal(Marshal.SizeOf<T>());
        allocations.Add(ptr);
        Marshal.StructureToPtr(value, ptr, false);
        return ptr;
    }

    private static void Grant(string path, SecurityIdentifier sid, FileSystemRights rights,
        AccessControlType type, List<(string Path, FileSystemAccessRule Rule)> grants)
    {
        bool directory = Directory.Exists(path);
        var rule = new FileSystemAccessRule(sid, rights,
            directory ? InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit : InheritanceFlags.None,
            PropagationFlags.None, type);
        grants.Add((path, rule)); // Keep rollback information even if propagation fails midway.
        EditAcl(path, rule, true);
    }

    private static void EditAcl(string path, FileSystemAccessRule rule, bool add)
    {
        CheckAncestors(path);
        string key = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(path.ToUpperInvariant())));
        using var mutex = new Mutex(false, "Local\\DevCLI.ACL." + key);
        try { if (!mutex.WaitOne(TimeSpan.FromSeconds(10))) throw new IOException("ACL update busy"); }
        catch (AbandonedMutexException) { }
        try
        {
            if (Directory.Exists(path))
            {
                var directory = new DirectoryInfo(path);
                var acl = directory.GetAccessControl(AccessControlSections.Access);
                if (!add && !acl.GetAccessRules(true, false, typeof(SecurityIdentifier))
                    .Cast<FileSystemAccessRule>().Any(r => r.IdentityReference.Equals(rule.IdentityReference))) return;
                if (add) acl.AddAccessRule(rule); else acl.RemoveAccessRuleSpecific(rule);
                directory.SetAccessControl(acl);
            }
            else if (File.Exists(path))
            {
                var file = new FileInfo(path);
                var acl = file.GetAccessControl(AccessControlSections.Access);
                if (!add && !acl.GetAccessRules(true, false, typeof(SecurityIdentifier))
                    .Cast<FileSystemAccessRule>().Any(r => r.IdentityReference.Equals(rule.IdentityReference))) return;
                if (add) acl.AddAccessRule(rule); else acl.RemoveAccessRuleSpecific(rule);
                file.SetAccessControl(acl);
            }
            else if (add) throw new IOException("Grant path missing");
        }
        finally { mutex.ReleaseMutex(); }
    }

    private static string CanonicalDirectory(string path)
    {
        if (!Path.IsPathFullyQualified(path) || path.StartsWith("\\\\", StringComparison.Ordinal))
            throw new IOException("Only absolute local directories are supported");
        path = Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar);
        if (!Directory.Exists(path) || path.Length <= 3) throw new IOException("Invalid grant root");
        CheckAncestors(path);
        return path;
    }

    private static bool Within(string path, string root) => string.Equals(path, root, StringComparison.OrdinalIgnoreCase)
        || path.StartsWith(root.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase);

    private static void CheckAncestors(string path)
    {
        for (string? current = path; current != null; current = Path.GetDirectoryName(current))
            if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Reparse points are not supported in sandbox paths");
    }

    private static void CheckTree(string root)
    {
        var pending = new Stack<string>();
        pending.Push(root);
        while (pending.TryPop(out string? directory))
            foreach (string path in Directory.EnumerateFileSystemEntries(directory))
            {
                var attributes = File.GetAttributes(path);
                if ((attributes & FileAttributes.ReparsePoint) != 0) throw new IOException("Sandbox tree contains a reparse point");
                if ((attributes & FileAttributes.Directory) != 0) pending.Push(path);
                else
                {
                    using var handle = File.OpenHandle(path, FileMode.Open, FileAccess.Read,
                        FileShare.ReadWrite | FileShare.Delete);
                    Check(Win32.GetFileInformationByHandle(handle, out var info), "Inspect file links");
                    if (info.links != 1) throw new IOException("Hard-linked files are not supported in sandbox trees");
                }
            }
    }

    private static string Quote(string value)
    {
        var result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in value)
        {
            if (c == '\\') { slashes++; continue; }
            result.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
            result.Append(c);
            slashes = 0;
        }
        return result.Append('\\', slashes * 2).Append('"').ToString();
    }

    private static void Check(bool success, string operation)
    {
        if (!success) throw new Win32Exception(Marshal.GetLastWin32Error(), operation);
    }
}
