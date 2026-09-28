using System;
using System.IO;
using System.IO.Ports;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;

namespace Feather.Link
{
    /// <summary>
    /// Same contract as the Android MachineLink, so the WPF UI's Start/Stop
    /// button and link-mode dropdown work identically on both platforms.
    /// </summary>
    public interface IMachineLink
    {
        Task ConnectAsync();
        Task SendGcodeAsync(string gcode, IProgress<(int sent, int total)> progress);
        void Stop(); // emergency stop — bypasses the queue, sends immediately
        void Disconnect();
    }

    /// <summary>
    /// Serial transport: works for a USB-tethered controller AND for
    /// Bluetooth Classic SPP devices, which Windows exposes as a virtual
    /// COM port — so this one class covers "USB" and "Bluetooth" on
    /// Windows without needing the BLE GATT APIs at all, matching how most
    /// hobby CNC/engraver controllers (GRBL, Marlin) actually connect.
    /// </summary>
    public class SerialMachineLink : IMachineLink
    {
        private readonly SerialPort _port;
        private TaskCompletionSource<bool> _okReceived;

        public SerialMachineLink(string comPort, int baud = 115200)
        {
            _port = new SerialPort(comPort, baud) { NewLine = "\n" };
            _port.DataReceived += OnDataReceived;
        }

        public Task ConnectAsync()
        {
            _port.Open();
            return Task.CompletedTask;
        }

        public async Task SendGcodeAsync(string gcode, IProgress<(int, int)> progress)
        {
            var lines = gcode.Split('\n', StringSplitOptions.RemoveEmptyEntries);
            for (int i = 0; i < lines.Length; i++)
            {
                _okReceived = new TaskCompletionSource<bool>();
                _port.WriteLine(lines[i].TrimEnd('\r'));
                await _okReceived.Task; // GRBL-style: wait for "ok" before next line
                progress?.Report((i + 1, lines.Length));
            }
        }

        private void OnDataReceived(object sender, SerialDataReceivedEventArgs e)
        {
            var line = _port.ReadLine();
            if (line.Trim().Equals("ok", StringComparison.OrdinalIgnoreCase))
                _okReceived?.TrySetResult(true);
            // "error:N" lines should surface to the UI via a separate event in a full build.
        }

        public void Stop()
        {
            // 0x18 = GRBL soft-reset/stop, sent as a raw byte outside the ack queue.
            _port.BaseStream.WriteByte(0x18);
            _port.BaseStream.Flush();
        }

        public void Disconnect() => _port.Close();
    }

    /// <summary>Wi-Fi transport for ESP32/network-enabled controllers exposing a raw TCP G-code port.</summary>
    public class WifiMachineLink : IMachineLink
    {
        private readonly string _host;
        private readonly int _port;
        private TcpClient _client;
        private StreamWriter _writer;
        private StreamReader _reader;

        public WifiMachineLink(string host, int port = 23) { _host = host; _port = port; }

        public async Task ConnectAsync()
        {
            _client = new TcpClient();
            await _client.ConnectAsync(_host, _port);
            var stream = _client.GetStream();
            _writer = new StreamWriter(stream, Encoding.ASCII) { AutoFlush = true, NewLine = "\n" };
            _reader = new StreamReader(stream, Encoding.ASCII);
        }

        public async Task SendGcodeAsync(string gcode, IProgress<(int, int)> progress)
        {
            var lines = gcode.Split('\n', StringSplitOptions.RemoveEmptyEntries);
            for (int i = 0; i < lines.Length; i++)
            {
                await _writer.WriteLineAsync(lines[i].TrimEnd('\r'));
                string resp;
                do { resp = await _reader.ReadLineAsync(); } while (resp != null && !resp.Trim().Equals("ok", StringComparison.OrdinalIgnoreCase));
                progress?.Report((i + 1, lines.Length));
            }
        }

        public void Stop() => _writer?.Write('\u0018'); // same GRBL stop byte over the socket
        public void Disconnect() => _client?.Close();
    }

    /// <summary>SD-card transport: exports a standard .gcode file for the machine's own reader.</summary>
    public class SdCardMachineLink : IMachineLink
    {
        private readonly string _targetPath;
        public SdCardMachineLink(string targetPath) { _targetPath = targetPath; }
        public Task ConnectAsync() => Task.CompletedTask;
        public Task SendGcodeAsync(string gcode, IProgress<(int, int)> progress)
        {
            File.WriteAllText(_targetPath, gcode);
            progress?.Report((1, 1));
            return Task.CompletedTask;
        }
        public void Stop() { /* no live session; card is removed or machine panel is used */ }
        public void Disconnect() { }
    }
}
