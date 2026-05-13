#!/bin/bash
set -e

# SSH files mounted from host belong to the host user, not root.
# Copy to /run/ssh so root owns them — SSH refuses files with wrong ownership.
if [ -d /root/.ssh-src ] && [ "$(ls -A /root/.ssh-src)" ]; then
    mkdir -p /run/.ssh
    cp -r /root/.ssh-src/. /run/.ssh/
    chown -R root:root /run/.ssh
    chmod 700 /run/.ssh
    find /run/.ssh -type f -not -name "*.pub" -exec chmod 600 {} \;
    find /run/.ssh -name "*.pub" -exec chmod 644 {} \;
    export HOME=/run
fi

exec java -jar /app/app.jar