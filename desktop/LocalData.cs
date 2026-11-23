using System.IO;
using System.Security.Cryptography;
using System.Text.Json;

namespace Roch.Desktop;

internal sealed class LocalData
{
    private static readonly string[] CredentialNames = ["firecrawl", "amapGeo", "amapWeather"];
    private readonly string storageFile;
    private readonly string credentialsFile;

    internal string Root { get; }
    internal string Logs { get; }
    internal StorageSettings Storage { get; private set; }

    internal LocalData()
    {
        Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Roch");
        Logs = Directory.CreateDirectory(Path.Combine(Root, "logs")).FullName;
        storageFile = Path.Combine(Root, "storage.json");
        credentialsFile = Path.Combine(Root, "credentials.bin");
        Storage = File.Exists(storageFile)
            ? JsonSerializer.Deserialize<StorageSettings>(File.ReadAllBytes(storageFile))
                ?? throw new InvalidDataException("存储服务设置无效。")
            : new("roch-desktop-" + RandomNumberGenerator.GetHexString(12, true), 15432, 19531,
                RandomNumberGenerator.GetHexString(48), RandomNumberGenerator.GetHexString(48));
        if (!File.Exists(storageFile)) SaveStorage(Storage.PostgresPort, Storage.MilvusPort);
    }

    internal void SaveStorage(int postgres, int milvus)
    {
        if (postgres is < 1024 or > 65535 || milvus is < 1024 or > 65535 || postgres == milvus)
            throw new ArgumentException("请输入两个不同的有效本机端口（1024–65535）。");
        var updated = Storage with { PostgresPort = postgres, MilvusPort = milvus };
        WriteAtomic(storageFile, JsonSerializer.SerializeToUtf8Bytes(updated));
        Storage = updated;
    }

    internal Dictionary<string, string> ReadCredentials()
    {
        if (!File.Exists(credentialsFile)) return new(StringComparer.Ordinal);
        var decrypted = ProtectedData.Unprotect(File.ReadAllBytes(credentialsFile), null, DataProtectionScope.CurrentUser);
        try
        {
            return JsonSerializer.Deserialize<Dictionary<string, string>>(decrypted)
                ?? throw new InvalidDataException("密钥存储无效。");
        }
        finally
        {
            CryptographicOperations.ZeroMemory(decrypted);
        }
    }

    internal Dictionary<string, bool> CredentialStatus()
    {
        var saved = ReadCredentials();
        return CredentialNames.ToDictionary(name => name, saved.ContainsKey);
    }

    internal void UpdateCredentials(Dictionary<string, string> updates)
    {
        var saved = ReadCredentials();
        foreach (var (name, value) in updates)
        {
            if (!CredentialNames.Contains(name) || value is null || value.Length > 4096 || value.Any(char.IsControl))
                throw new ArgumentException("密钥格式无效。");
            if (string.IsNullOrWhiteSpace(value)) saved.Remove(name);
            else saved[name] = value.Trim();
        }

        var cleartext = JsonSerializer.SerializeToUtf8Bytes(saved);
        try
        {
            var encrypted = ProtectedData.Protect(cleartext, null, DataProtectionScope.CurrentUser);
            WriteAtomic(credentialsFile, encrypted);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(cleartext);
        }
    }

    private static void WriteAtomic(string path, byte[] content)
    {
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllBytes(temporary, content);
            File.Move(temporary, path, overwrite: true);
        }
        finally
        {
            File.Delete(temporary);
        }
    }
}

internal sealed record StorageSettings(string Project, int PostgresPort, int MilvusPort,
    string PostgresPassword, string MinioPassword);
