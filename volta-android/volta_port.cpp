// Android-Port von Volta OS: legt beim Start die mitgelieferten Dateien (services/, assets/)
// aus dem APK in den App-Speicher und zeigt mit VOLTA_DIR darauf. GPL-3.0-or-later
#include <QCoreApplication>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QStandardPaths>
#include <QDebug>

#ifndef VOLTA_PORT_STAMP
#define VOLTA_PORT_STAMP "dev"
#endif

// Die Android-Dateiauswahl gibt keine Pfade, sondern content://-Adressen zurueck. Volta erwartet
// eine Datei mit Endung: also in den Zwischenspeicher kopieren (Endung anhand des Inhalts).
QString voltaPortLocalFile(const QString &path)
{
#ifdef Q_OS_ANDROID
    if (!path.startsWith(QLatin1String("content:"))) return path;
    QFile in(path);
    if (!in.open(QIODevice::ReadOnly)) { qWarning().noquote() << "[port] cannot open" << path; return path; }
    const QByteArray head = in.peek(4);
    QString name = QFileInfo(path).completeBaseName();
    if (name.isEmpty() || name.size() > 60 || name.contains(QLatin1Char('/')) || name.contains(QLatin1Char('%'))
        || name.contains(QLatin1Char(':')))
        name = QStringLiteral("car");
    QString ext = QFileInfo(path).suffix().toLower();
    if (head == "glTF") ext = QStringLiteral("glb");
    if (ext.isEmpty() || ext.size() > 5) ext = QStringLiteral("bin");
    const QString dir = QDir::tempPath() + QStringLiteral("/volta_pick");
    QDir(dir).removeRecursively();
    QDir().mkpath(dir);
    const QString out = dir + QLatin1Char('/') + name + QLatin1Char('.') + ext;
    QFile to(out);
    if (!to.open(QIODevice::WriteOnly)) return path;
    while (!in.atEnd()) {
        const QByteArray chunk = in.read(1 << 20);
        if (chunk.isEmpty()) break;
        to.write(chunk);
    }
    to.close();
    qInfo().noquote() << "[port] picked file copied to" << out << to.size() << "bytes";
    return out;
#else
    return path;
#endif
}

static void voltaPortPrepare()
{
#ifdef Q_OS_ANDROID
    const QString src = QStringLiteral("assets:/volta");
    const QString dst = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/volta");
    const QString stampFile = dst + QStringLiteral("/.port_stamp");
    QByteArray have;
    { QFile f(stampFile); if (f.open(QIODevice::ReadOnly)) have = f.readAll(); }
    if (have != QByteArray(VOLTA_PORT_STAMP)) {
        int n = 0;
        QDirIterator it(src, QDir::Files, QDirIterator::Subdirectories);
        while (it.hasNext()) {
            const QString from = it.next();
            const QString to = dst + from.mid(src.size());
            QDir().mkpath(QFileInfo(to).absolutePath());
            QFile::remove(to);
            if (QFile::copy(from, to)) {
                QFile::setPermissions(to, QFile::ReadOwner | QFile::WriteOwner);
                ++n;
            }
        }
        QFile f(stampFile);
        if (f.open(QIODevice::WriteOnly)) f.write(VOLTA_PORT_STAMP);
        qInfo().noquote() << "[port] files unpacked:" << n << "->" << dst;
    }
    qputenv("VOLTA_DIR", dst.toUtf8());
#endif
}
Q_COREAPP_STARTUP_FUNCTION(voltaPortPrepare)
