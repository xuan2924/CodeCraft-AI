FROM scratch
COPY --chown=10001:10001 --chmod=0700 state/ /var/lib/codecraft/
COPY --chmod=0555 sandbox /sandbox
USER 10001:10001
WORKDIR /var/lib/codecraft
ENTRYPOINT ["/sandbox", "--container"]
