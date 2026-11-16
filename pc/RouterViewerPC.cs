using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
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

    // Same palette as the phone app (src/main/res/values/colors.xml).
    static readonly Color Bg = Color.FromArgb(0x00, 0x00, 0x00);
    static readonly Color Card = Color.FromArgb(0x11, 0x13, 0x15);
    static readonly Color Line = Color.FromArgb(0x2A, 0x2E, 0x32);
    static readonly Color Fg = Color.FromArgb(0xF2, 0xF2, 0xF2);
    static readonly Color Dim = Color.FromArgb(0xA8, 0xAE, 0xB4);
    static readonly Color Off = Color.FromArgb(0x5A, 0x60, 0x66);
    static readonly Color Accent = Color.FromArgb(0xFF, 0xB4, 0x54);
    static readonly Color Bad = Color.FromArgb(0xFF, 0x8A, 0x80);

    readonly Label dot = new Label { Location = new Point(14, 14), Size = new Size(26, 32), Font = new Font("Malgun Gothic", 14f), Text = "●" };
    readonly Label title = new Label { Location = new Point(42, 14), Size = new Size(320, 32), ForeColor = Fg, Font = new Font("Malgun Gothic", 15f, FontStyle.Bold) };
    readonly Label detail = new Label { Location = new Point(44, 50), Size = new Size(320, 22), ForeColor = Dim, Font = new Font("Malgun Gothic", 9.5f) };
    readonly Button toggle = new Button { Location = new Point(20, 164), Size = new Size(380, 46), FlatStyle = FlatStyle.Flat, Font = new Font("Malgun Gothic", 11.5f, FontStyle.Bold), Cursor = Cursors.Hand };
    readonly TextBox log = new TextBox { Location = new Point(20, 250), Size = new Size(380, 90), Multiline = true, ReadOnly = true, BorderStyle = BorderStyle.None, BackColor = Card, ForeColor = Dim, Font = new Font("Consolas", 8.5f), ScrollBars = ScrollBars.Vertical };
    readonly Timer timer = new Timer { Interval = 5000 };
    readonly NotifyIcon tray = new NotifyIcon();
    Process box;
    bool stopping, closing, checking, exiting, hintShown;
    bool? online;
    int checks;

    RouterViewerPC()
    {
        SuspendLayout();
        AutoScaleDimensions = new SizeF(96f, 96f);
        AutoScaleMode = AutoScaleMode.Dpi;
        Text = "Router Viewer PC";
        Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        BackColor = Bg;
        ForeColor = Fg;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        ClientSize = new Size(420, 360);

        var logo = new PictureBox { Location = new Point(20, 18), Size = new Size(28, 28), SizeMode = PictureBoxSizeMode.Zoom, Image = Icon.ToBitmap() };
        var name = new Label { Location = new Point(56, 20), AutoSize = true, ForeColor = Fg, Font = new Font("Malgun Gothic", 12f, FontStyle.Bold), Text = "Router Viewer PC" };
        var proxy = new Label { Location = new Point(250, 24), Size = new Size(150, 20), ForeColor = Off, Font = new Font("Malgun Gothic", 9f), TextAlign = ContentAlignment.TopRight, Text = Phone + ":" + Port };
        var card = new Panel { Location = new Point(20, 64), Size = new Size(380, 86), BackColor = Card };
        card.Controls.AddRange(new Control[] { dot, title, detail });
        var logTitle = new Label { Location = new Point(20, 226), AutoSize = true, ForeColor = Off, Font = new Font("Malgun Gothic", 9f), Text = "기록" };
        Controls.AddRange(new Control[] { logo, name, proxy, card, toggle, logTitle, log });
        ResumeLayout(false);
        PerformLayout();

        var menu = new ContextMenuStrip();
        menu.Items.Add("열기", null, (s, e) => Restore());
        menu.Items.Add("종료", null, (s, e) => { exiting = true; Close(); });
        tray.Icon = Icon;
        tray.ContextMenuStrip = menu;
        tray.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) Restore(); };
        tray.Visible = true;

        log.HandleCreated += (s, e) => SetWindowTheme(log.Handle, "DarkMode_Explorer", null);
        SetStatus(Off, "연결 안 됨", "아래 버튼을 눌러 연결하세요");
        SetButton(false);
        toggle.Click += (s, e) => { if (box == null) Connect(); else Disconnect(); };
        Shown += (s, e) => Connect();
        FormClosing += (s, e) =>
        {
            if (!exiting && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                if (!hintShown) tray.ShowBalloonTip(3000, "Router Viewer PC", "트레이에서 계속 실행됩니다. 끄려면 아이콘을 우클릭해 종료하세요.", ToolTipIcon.None);
                hintShown = true;
                return;
            }
            closing = true;
            Disconnect();
            tray.Visible = false;
        };
        timer.Tick += (s, e) => Check();
        timer.Start();
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        int dark = 1;
        DwmSetWindowAttribute(Handle, 20, ref dark, 4);
    }

    void Restore()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    void SetStatus(Color c, string head, string sub)
    {
        dot.ForeColor = c;
        title.Text = head;
        detail.Text = sub;
        tray.Text = "Router Viewer PC: " + head;
    }

    void SetButton(bool connected)
    {
        toggle.Text = connected ? "연결 끊기" : "연결";
        toggle.BackColor = connected ? Card : Accent;
        toggle.ForeColor = connected ? Fg : Bg;
        toggle.FlatAppearance.BorderColor = connected ? Line : Accent;
        toggle.FlatAppearance.BorderSize = 1;
        toggle.FlatAppearance.MouseOverBackColor = connected ? Line : Color.FromArgb(0xFF, 0xC4, 0x78);
        toggle.FlatAppearance.MouseDownBackColor = toggle.FlatAppearance.MouseOverBackColor;
    }

    async void Connect()
    {
        toggle.Enabled = false;
        SetStatus(Dim, "폰 프록시 확인 중...", Phone + ":" + Port + "에 연결하는 중");
        bool reachable = await Task.Run(() => Reachable());
        if (closing) return;
        if (!reachable)
        {
            SetStatus(Bad, "폰 프록시에 연결할 수 없음", "핫스팟 연결과 폰 앱의 프록시를 확인하세요");
            SetButton(false);
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
            SetStatus(Bad, "sing-box 실행 실패", "아래 기록을 확인하세요");
            Append(ex.Message);
            SetButton(false);
            toggle.Enabled = true;
            return;
        }
        AssignProcessToJobObject(Job, box.Handle);
        box.BeginErrorReadLine();
        online = null;
        SetStatus(Dim, "연결 확인 중...", "폰을 거쳐 인터넷에 연결되는지 확인하는 중");
        SetButton(true);
        toggle.Enabled = true;
    }

    void Disconnect()
    {
        if (box == null || box.HasExited) return;
        stopping = true;
        toggle.Enabled = false;
        SetStatus(Dim, "끊는 중...", "원래 연결로 되돌리는 중");
        // Ctrl+C lets sing-box remove its routes and firewall rules; Kill would skip that.
        if (AttachConsole((uint)box.Id))
        {
            SetConsoleCtrlHandler(IntPtr.Zero, true);
            GenerateConsoleCtrlEvent(0, 0);
            FreeConsole();
        }
        else Append("sing-box에 종료 신호를 보내지 못했습니다 (오류 " + Marshal.GetLastWin32Error() + ")");
        if (!box.WaitForExit(5000))
        {
            Append("sing-box가 5초 안에 끝나지 않아 강제 종료했습니다");
            box.Kill();
        }
        SetConsoleCtrlHandler(IntPtr.Zero, false);
    }

    void Exited()
    {
        box = null;
        if (stopping) SetStatus(Off, "연결 끊김", "핫스팟으로 직접 연결됩니다");
        else SetStatus(Bad, "연결이 끊어졌습니다", "아래 기록을 확인하세요");
        stopping = false;
        SetButton(false);
        toggle.Enabled = true;
    }

    async void Check()
    {
        if (box == null || stopping || checking) return;
        checking = true;
        bool phone = await Task.Run(() => Reachable());
        bool tun = phone && ViaTun();
        if (!tun) online = null;
        else if (online != true || ++checks % 3 == 0) online = await Task.Run(() => Online());
        checking = false;
        if (box == null || stopping || closing) return;
        if (!phone) SetStatus(Bad, "폰 프록시 응답 없음", "인터넷이 멈춥니다. 핫스팟과 폰 앱을 확인하세요");
        else if (!tun) SetStatus(Bad, "프록시를 거치지 않음", "트래픽이 TUN으로 가지 않습니다 (다른 VPN 확인)");
        else if (online != true) SetStatus(Bad, "인터넷 안 됨", "폰은 응답하지만 모바일 데이터로 나가지 못합니다");
        else SetStatus(Accent, "연결됨", "노트북의 모든 트래픽이 폰을 거칩니다");
    }

    // Source address Windows picks for an internet destination (no packet is sent); must match the tun address in config.json.
    static bool ViaTun()
    {
        try
        {
            using (var u = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp))
            {
                u.Connect("8.8.8.8", 53);
                return ((IPEndPoint)u.LocalEndPoint).Address.ToString().StartsWith("172.19.0.");
            }
        }
        catch
        {
            return false;
        }
    }

    // Checked only after ViaTun(), so this goes TUN, sing-box, phone, mobile data and a 204 means the whole path works.
    static bool Online()
    {
        try
        {
            var r = (HttpWebRequest)WebRequest.Create("http://connectivitycheck.gstatic.com/generate_204");
            r.Proxy = null;
            r.Timeout = 5000;
            r.ReadWriteTimeout = 5000;
            r.KeepAlive = false;
            using (var resp = (HttpWebResponse)r.GetResponse()) return resp.StatusCode == HttpStatusCode.NoContent;
        }
        catch
        {
            return false;
        }
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

    [DllImport("kernel32.dll", SetLastError = true)] static extern bool AttachConsole(uint pid);
    [DllImport("kernel32.dll")] static extern bool FreeConsole();
    [DllImport("kernel32.dll")] static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
    [DllImport("kernel32.dll")] static extern bool GenerateConsoleCtrlEvent(uint ctrlEvent, uint groupId);
    // Kill-on-close job: if this app dies, Windows ends sing-box too instead of leaving it holding the routes.
    static readonly IntPtr Job = KillOnCloseJob();

    static IntPtr KillOnCloseJob()
    {
        IntPtr job = CreateJobObject(IntPtr.Zero, null);
        var limits = new JobLimits { LimitFlags = 0x2000 };
        SetInformationJobObject(job, 9, ref limits, Marshal.SizeOf(limits));
        return job;
    }

    // JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    [StructLayout(LayoutKind.Sequential)]
    struct JobLimits
    {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr attrs, string name);
    [DllImport("kernel32.dll")] static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref JobLimits info, int size);
    [DllImport("kernel32.dll")] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
    [DllImport("uxtheme.dll", CharSet = CharSet.Unicode)] static extern int SetWindowTheme(IntPtr hwnd, string app, string idList);

    [STAThread]
    static void Main()
    {
        Application.EnableVisualStyles();
        Application.Run(new RouterViewerPC());
    }
}
