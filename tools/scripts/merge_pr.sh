#!/usr/bin/env bash
# usage: tools/scripts/merge_pr.sh <pr-number> "<squash subject>"  (loops until merged or 40 min; requires `build` and, when present, `ios` checks; updates the branch when BEHIND)
set -u; N=$1; SUBJ=$2; REPO=RaheemJnr/pocket-node; deadline=$(( $(date +%s) + 2400 ))
while [ $(date +%s) -lt $deadline ]; do
  st=$(gh pr view $N --repo $REPO --json state,mergeStateStatus -q '.state+" "+.mergeStateStatus' 2>/dev/null)
  case "$st" in
    "MERGED "*) echo "#$N MERGED"; exit 0;;
    "CLOSED "*) echo "#$N CLOSED (not merged)"; exit 1;;
    "OPEN BEHIND") gh api -X PUT repos/$REPO/pulls/$N/update-branch >/dev/null 2>&1 && echo "#$N updated branch from main"; sleep 60; continue;;
    "OPEN BLOCKED"|"OPEN UNKNOWN"|"OPEN UNSTABLE")
      b=$(gh pr checks $N --repo $REPO --json name,bucket 2>/dev/null | python3 -c "
import json,sys; d=json.load(sys.stdin)
bl=[c['bucket'] for c in d if c['name']=='build']; il=[c['bucket'] for c in d if c['name']=='ios']
b=bl[0] if bl else 'none'; i=il[0] if il else 'absent'
print('fail' if 'fail' in (b,i) else ('pass' if b=='pass' and i in ('pass','absent','skipping') else 'pending'))")
      if [ "$b" = "fail" ]; then echo "#$N build or ios check FAILED"; exit 2; fi
      if [ "$b" = "pass" ]; then gh pr merge $N --repo $REPO --squash --subject "$SUBJ" >/dev/null 2>&1 || true; fi
      sleep 45; continue;;
    "OPEN CLEAN"|"OPEN HAS_HOOKS") gh pr merge $N --repo $REPO --squash --subject "$SUBJ" >/dev/null 2>&1 || true; sleep 20; continue;;
    "OPEN DIRTY") echo "#$N DIRTY (conflicts), needs manual rebase"; exit 3;;
    *) sleep 30;;
  esac
done; echo "#$N timeout"; exit 4
