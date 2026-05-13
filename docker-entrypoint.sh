#!/bin/bash
set -e

# SSH files mounted from host belong to the host user, not root.
# Copy to /run/ssh so root owns them — SSH refuses files with wrong ownership.
if [ -d /root/.ssh-src ] && [ "$(ls -A /root/.ssh-src)" ]; then
    mkdir -p /root/.ssh
    cp -r /root/.ssh-src/. /root/.ssh/
    chown -R root:root /root/.ssh
    chmod 700 /root/.ssh
    find /root/.ssh -type f -not -name "*.pub" -exec chmod 600 {} \;
    find /root/.ssh -name "*.pub" -exec chmod 644 {} \;
    ssh-keyscan -p 443 altssh.bitbucket.org >> /root/.ssh/known_hosts 2>/dev/null
fi

exec java -jar /app/app.jar