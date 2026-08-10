// The FrameCast icon on the thing the user actually double-clicks.
//
// A .vbs cannot carry its own icon: Explorer draws whatever the Windows
// Script Host file type says, so the installer showed a generic script glyph
// no matter what shipped beside it. This is the smallest possible native
// launcher — it does nothing but start the real installer, which is left
// exactly as it is — compiled with /win32icon so the brand mark is on the
// entry point.
//
// Built by installer/build_launcher.ps1 with the csc that ships with the
// .NET Framework, so there is no toolchain to install.

using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Windows.Forms;

internal static class Launcher
{
    [STAThread]
    private static void Main()
    {
        string here = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
        string script = Path.Combine(here, "Install_FrameCast.vbs");

        // The one failure worth a message: the ZIP was extracted partially, or
        // this file was copied out of it on its own. Everything else is the
        // installer's own business and it reports its own errors.
        if (!File.Exists(script))
        {
            MessageBox.Show(
                "Install_FrameCast.vbs is missing next to this program.\r\n\r\n" +
                "Extract the whole ZIP (keeping the pc_receiver folder beside " +
                "this file) and run it again.\r\n\r\n" +
                "Falta Install_FrameCast.vbs junto a este programa. Extrae el " +
                "ZIP completo, con la carpeta pc_receiver al lado, y vuelve a " +
                "intentarlo.",
                "FrameCast", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        var start = new ProcessStartInfo("wscript.exe", "\"" + script + "\"");
        start.WorkingDirectory = here;
        start.UseShellExecute = false;
        try
        {
            Process.Start(start);
        }
        catch (Exception e)
        {
            MessageBox.Show(
                "Could not start the installer: " + e.Message,
                "FrameCast", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }
}
