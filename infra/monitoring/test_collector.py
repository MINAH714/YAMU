import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from collector import Collector, api_metrics, observe, parse_build, redact, request_sample, tail


class PrivacyAndMetrics(unittest.TestCase):
    def test_api_metadata_is_bounded_and_never_copies_unknown_path_values(self):
        sample=request_sample(json.dumps({'route':'/api/users/secret-person','method':'MADEUP','status':401,'duration':.25,'body':'never-export'}),{'/api/health'})
        self.assertEqual(sample,('/api/other','OTHER',401,.25))
        counts={};observe(counts,sample)
        text='\n'.join(api_metrics(counts))
        self.assertNotIn('secret-person',text)
        self.assertNotIn('never-export',text)
        self.assertIn('status="401"',text)

    def test_bad_or_infinite_durations_do_not_poison_prometheus(self):
        for duration in ('NaN','Infinity',-1,4000):
            with self.subTest(duration=duration):
                self.assertIsNone(request_sample(json.dumps({'route':'/api/health','method':'GET','status':200,'duration':duration}),{'/api/health'}))

    def test_histogram_cumulative_buckets_and_restart_serialization(self):
        counts={}
        observe(counts,('/api/health','GET',200,.1))
        counts=json.loads(json.dumps(counts))
        observe(counts,('/api/health','GET',200,.3))
        text='\n'.join(api_metrics(counts))
        self.assertIn('le="0.1"} 1',text)
        self.assertIn('le="0.5"} 2',text)
        self.assertIn('le="+Inf"} 2',text)

    def test_secret_formats_and_pii_are_redacted(self):
        cases=[('Authorization: Bearer my-demo-token','my-demo-token'),
               ('Authorization: Basic abcdefghijk','abcdefghijk'),
               ('{"password":"many secret words"}','many secret words'),
               ('YAMU_CRYPTO_DATA_KEY_1=not-a-real-key','not-a-real-key'),
               ('SSAFY_API_KEY=not-a-real-api-key','not-a-real-api-key'),
               ('userKey=not-a-real-user-key','not-a-real-user-key'),
               ('card=1234-5678-1234-5678','1234-5678-1234-5678'),
               ('phone 010-1234-5678','010-1234-5678'),
               ('person=demo@example.com','demo@example.com'),
               ('-----BEGIN PRIVATE KEY-----\nnot-a-real-private-key\n-----END PRIVATE KEY-----','not-a-real-private-key')]
        for message,secret in cases:
            with self.subTest(secret_type=message.split('=')[0][:20]):
                self.assertNotIn(secret,redact(message))


class RotationAndState(unittest.TestCase):
    def test_starting_or_unknown_health_is_never_green(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);routes=root/'routes.json';routes.write_text('[]')
            c=Collector({'root':str(root/'data'),'routes_file':str(routes)})
            self.assertTrue((root/'data/public/logs/api.jsonl').exists())
            self.assertTrue((root/'data/public/logs/jenkins-cd.jsonl').exists())
            for health in ('starting','unhealthy','unknown'):
                info={'id':'test','image':'test:revision','revision':'test','state':'running',
                      'health':health,'started_at':'2026-11-08T00:00:00Z','log_path':''}
                with self.subTest(health=health),patch('collector.run',return_value=json.dumps(info)),patch.object(c,'emit'):
                    self.assertIn('yamu_service_up{service="backend"} 0',c.services())

    def test_log_rotation_error_never_duplicates_or_reverses_service_health(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);routes=root/'routes.json';routes.write_text('[]')
            c=Collector({'root':str(root/'data'),'routes_file':str(routes)})
            info={'id':'test','image':'test:revision','revision':'test','state':'running',
                  'health':'healthy','started_at':'2026-11-08T00:00:00Z','log_path':'/synthetic/log'}
            with patch('collector.run',return_value=json.dumps(info)),patch.object(c,'read',side_effect=OSError),patch.object(c,'emit'):
                lines=c.services()
            self.assertEqual([x for x in lines if x.startswith('yamu_service_up{service="backend"')],['yamu_service_up{service="backend"} 1'])
            self.assertEqual(c.state['log_gaps'],2)

    def test_incomplete_line_is_not_lost_or_counted_twice(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'log';path.write_bytes(b'one\ntw')
            lines,cursor,_=tail(path,None)
            self.assertEqual(lines,['one'])
            with path.open('ab') as f:f.write(b'o\n')
            lines,cursor,_=tail(path,cursor)
            self.assertEqual(lines,['two'])
            self.assertEqual(tail(path,cursor)[0],[])

    def test_rotation_reads_remaining_old_inode_then_new_file(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'log';path.write_bytes(b'old\n')
            _,cursor,_=tail(path,None)
            with path.open('ab') as f:f.write(b'old-final\n')
            path.rename(Path(folder)/'log.1');path.write_bytes(b'new\n')
            lines,_,gaps=tail(path,cursor)
            self.assertEqual(lines,['old-final','new']);self.assertEqual(gaps,0)

    def test_missing_rotated_file_is_explicitly_counted(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'log';path.write_bytes(b'one\n')
            _,cursor,_=tail(path,None)
            path.rename(Path(folder)/'different');path.write_bytes(b'two\n')
            self.assertEqual(tail(path,cursor)[2],1)

    def test_oversized_line_does_not_permanently_block_tail(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'log';path.write_bytes(b'x'*40000+b'\nvalid\n')
            lines,_,gaps=tail(path,None)
            self.assertEqual(lines,['valid']);self.assertEqual(gaps,1)

    def test_counter_checkpoint_prevents_double_count_on_collector_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);route=root/'routes.json';route.write_text('["/api/health"]')
            access=root/'nginx';access.write_text('{"route":"/api/health","method":"GET","status":200,"duration":0.1}\n')
            config={'root':str(root/'data'),'routes_file':str(route),'nginx_log':str(access),'jenkins_home':str(root/'jenkins')}
            original=Path.read_text
            def read(path,*args,**kwargs):
                if path.as_posix()=='/proc/meminfo':return 'MemTotal: 2000 kB\nMemAvailable: 1000 kB\n'
                if path.as_posix()=='/proc/stat':return 'cpu 1 2 3 4 5 6 7 8\n'
                return original(path,*args,**kwargs)
            with patch.object(Collector,'services',return_value=[]),patch.object(Path,'read_text',read):
                first=Collector(config);first.cycle()
                second=Collector(config);second.cycle()
            self.assertEqual(next(iter(second.state['api'].values()))['count'],1)
            self.assertNotIn('cursors',(root/'data/public/metrics/yamu.prom').read_text())

    def test_jenkins_failure_never_becomes_success_and_missing_result_is_running(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'build.xml'
            path.write_text('<build><result>FAILURE</result><duration>12000</duration><timestamp>100000</timestamp></build>')
            self.assertEqual(parse_build(path)['result'],'FAILURE')
            self.assertEqual(parse_build(path)['duration_seconds'],12)
            path.write_text('<build><timestamp>100000</timestamp></build>')
            self.assertEqual(parse_build(path)['result'],'RUNNING')


if __name__=='__main__':unittest.main()
