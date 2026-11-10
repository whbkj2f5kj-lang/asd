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
