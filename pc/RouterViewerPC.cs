using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

/** Runs sing-box (config.json next to this exe) so the whole laptop goes through the phone's SOCKS5 proxy. */
class RouterViewerPC : Form
{
    const string Phone = "192.168.43.1";
    const int Port = 1080;

    readonly Label status = new Label { Dock = DockStyle.Top, Height = 48, Font = new Font("Malgun Gothic", 13f), TextAlign = ContentAlignment.MiddleCenter };
    readonly Button toggle = new Button { Dock = DockStyle.Top, Height = 44, Font = new Font("Malgun Gothic", 11f), Text = "연결" };
    readonly TextBox log = new TextBox { Dock = DockStyle.Fill, Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Font = new Font("Consolas", 8.5f) };
    const string Connected = "연결됨: 노트북 전체가 폰을 거칩니다";
    readonly Timer timer = new Timer { Interval = 5000 };
    Process box;
    bool stopping, closing, checking;

    RouterViewerPC()
    {
        Text = "Router Viewer PC";
        ClientSize = new Size(460, 320);
        Controls.Add(log);
        Controls.Add(toggle);
        Controls.Add(status);
        toggle.Click += (s, e) => { if (box == null) Connect(); else Disconnect(); };
        Shown += (s, e) => Connect();
        FormClosing += (s, e) => { closing = true; Disconnect(); };
        timer.Tick += (s, e) => Check();
        timer.Start();
    }

    async void Connect()
    {
        toggle.Enabled = false;
        status.Text = "폰 프록시 확인 중...";
        bool reachable = await Task.Run(() => Reachable());
        if (closing) return;
        if (!reachable)
        {
            status.Text = "폰 프록시에 연결할 수 없음";
            Append("핫스팟에 연결되어 있는지, 폰에서 Router Viewer 앱이 떠 있고 프록시가 켜져 있는지 확인하세요.");
            toggle.Text = "연결";
            toggle.Enabled = true;
            return;
        }
        string dir = AppDomain.CurrentDomain.BaseDirectory;
        box = new Process
        {
            EnableRaisingEvents = true,
            StartInfo = new ProcessStartInfo(Path.Combine(dir, "sing-box.exe"), "run -c config.json")
            {
                WorkingDirectory = dir,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
                StandardErrorEncoding = Encoding.UTF8,
            },
        };
        box.ErrorDataReceived += (s, e) => { if (e.Data != null) Ui(() => Append(e.Data)); };
        box.Exited += (s, e) => Ui(Exited);
        try
        {
            box.Start();
        }
        catch (Exception ex)
        {
            box = null;
            status.Text = "sing-box 실행 실패";
            Append(ex.Message);
            toggle.Text = "연결";
            toggle.Enabled = true;
            return;
        }
        box.BeginErrorReadLine();
        status.Text = Connected;
        toggle.Text = "연결 끊기";
        toggle.Enabled = true;
    }

    void Disconnect()
    {
        if (box == null || box.HasExited) return;
        stopping = true;
        toggle.Enabled = false;
        status.Text = "끊는 중...";
        // Ctrl+C lets sing-box remove its routes and firewall rules; Kill would skip that.
        if (AttachConsole((uint)box.Id))
        {
            SetConsoleCtrlHandler(IntPtr.Zero, true);
            GenerateConsoleCtrlEvent(0, 0);
            FreeConsole();
        }
        if (!box.WaitForExit(5000)) box.Kill();
        SetConsoleCtrlHandler(IntPtr.Zero, false);
    }

    void Exited()
    {
        box = null;
        status.Text = stopping ? "연결 끊김" : "연결이 끊어졌습니다 (아래 기록 참고)";
        stopping = false;
        toggle.Text = "연결";
        toggle.Enabled = true;
    }

    void Ui(Action a)
    {
        try
        {
            if (!closing) BeginInvoke(a);
        }
        catch (InvalidOperationException)
        {
        }
    }

    void Append(string line)
    {
        log.AppendText(Regex.Replace(line, @"\x1b\[[0-9;]*m", "") + "\r\n");
    }

    async void Check()
    {
        if (box == null || stopping || checking) return;
        checking = true;
        bool ok = await Task.Run(() => Reachable());
        checking = false;
        if (box == null || stopping || closing) return;
        status.Text = ok ? Connected : "폰 프록시 응답 없음: 핫스팟과 폰 앱을 확인하세요";
    }

    // A real SOCKS5 greeting, because while connected a bare TCP connect could be answered by sing-box itself.
    static bool Reachable()
    {
        try
        {
            using (var c = new TcpClient())
            {
                if (!c.ConnectAsync(Phone, Port).Wait(2000)) return false;
                var s = c.GetStream();
                s.ReadTimeout = 2000;
                s.Write(new byte[] { 5, 1, 0 }, 0, 3);
                return s.ReadByte() == 5 && s.ReadByte() == 0;
            }
        }
        catch
        {
            return false;
        }
    }

    [DllImport("kernel32.dll")] static extern bool AttachConsole(uint pid);
    [DllImport("kernel32.dll")] static extern bool FreeConsole();
    [DllImport("kernel32.dll")] static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
    [DllImport("kernel32.dll")] static extern bool GenerateConsoleCtrlEvent(uint ctrlEvent, uint groupId);

    [STAThread]
    static void Main()
    {
        Application.EnableVisualStyles();
        Application.Run(new RouterViewerPC());
    }
}
