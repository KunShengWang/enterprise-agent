"""Package frozen original artifacts for offline review without rerunning the Agent."""
import argparse
import zipfile
from run import ROOT,EVAL,read,write,digest,manifest

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('run_id');args=parser.parse_args()
    run=ROOT/'target/procurement-v2'/args.run_id;reports=EVAL/'reports'
    if read(run/'freeze.json')!=manifest():raise RuntimeError('source changed after freeze')
    summary=read(reports/'b0-summary.json')
    if summary['run_id']!=args.run_id or not summary['complete']:raise RuntimeError('incomplete or wrong B0')
    index=[]
    with zipfile.ZipFile(reports/'b0-evidence.zip','w',compression=zipfile.ZIP_STORED) as bundle:
        for path in sorted(run.glob('*-a*/artifact.json.gz')):
            name=path.parent.name+'/artifact.json.gz';bundle.write(path,name)
            index.append(dict(path=name,sha256=digest(path),bytes=path.stat().st_size))
        for name in ('freeze.json','attempts.jsonl','summary.json'):bundle.write(run/name,name)
    write(reports/'b0-evidence-index.json',dict(run_id=args.run_id,archive_sha256=digest(reports/'b0-evidence.zip'),artifacts=index))
    write(EVAL/'fixtures/freeze-v2.1.json',read(run/'freeze.json'))
