import io
import json
import threading
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch

from server import AuthRequired, BadInput, Grafana, Handler, ThreadingHTTPServer, RAW_QUERY, log_query, logs, number, overview, parse_options


class Queries(unittest.TestCase):
    def test_unknown_upstream_or_unbounded_window_is_rejected(self):
        for value in ('url=https://untrusted.invalid','window=100y','window=15m&window=6h','source=unknown','source=api&build=5'):
            with self.subTest(query=value),self.assertRaises(BadInput):parse_options(value,logs=True)

    def test_search_is_literal_and_build_filter_is_fixed_numeric(self):
        search='"} |~ ".*'
        options=parse_options('source=jenkins-ci&build=112',logs=True);options['search']=search
        query=log_query(options)
        self.assertIn('| json | build="112"',query)
        self.assertTrue(query.endswith('|= '+json.dumps(search)))
        with self.assertRaises(BadInput):parse_options('source=jenkins-ci&build=112%22',logs=True)

    def test_cookie_filter_drops_unrelated_app_secrets_and_missing_cookie_is_401(self):
        client=Grafana('grafana_session=synthetic-session; unrelated=must-not-forward')
        self.assertEqual(client.cookies,{'grafana_session':'synthetic-session'})
        with self.assertRaises(AuthRequired):Grafana('unrelated=anything')
        with self.assertRaises(BadInput):client.get('api/admin/settings')
        with self.assertRaises(BadInput):client.get('https://untrusted.invalid/')

    def test_nan_infinity_and_absent_data_never_become_successful_zero(self):
        for value in ('NaN','+Inf',None):self.assertIsNone(number(value))
        class Empty:
            def prometheus(self,*args):return []
        result=overview(Empty(),'15m')
        self.assertIsNone(result['api']['total']);self.assertIsNone(result['api']['p95'])
        self.assertIsNone(result['collector_age_seconds']);self.assertEqual(result['services'],{})

    def test_partial_outage_is_explicit_not_a_fake_healthy_service(self):
        from server import Unavailable
        class Broken:
            def prometheus(self,*args):raise Unavailable()
        result=overview(Broken(),'15m')
        self.assertIn('metrics',result['errors']);self.assertEqual(result['services'],{})

    def test_raw_metrics_are_aggregated_without_cookie_or_user_fields(self):
        def metric(name,value,**labels):return {'metric':{'__name__':name,**labels},'value':[0,str(value)]}
        rows=[metric('yamu_service_up',1,service='backend'),metric('yamu_service_info',1,service='backend',revision='a'*40),
              metric('yamu_api_requests_total',2,route='/api/cards/{id}',status='401'),
              metric('yamu_api_requests_total',3,route='/api/cards/{id}',status='200'),
              metric('yamu_jenkins_build_result',1,job='collector',exported_job='ci',build='112',result='FAILURE')]
        class Fixture:
            def prometheus(self,query,*args):return rows if query==RAW_QUERY else []
        result=overview(Fixture(),'15m')
        self.assertEqual(result['api']['total'],5)
        self.assertEqual(result['api']['routes'][0]['client_errors'],2)
        self.assertEqual(result['builds'][0]['result'],'FAILURE')
        self.assertNotIn('cookies',result)

    def test_logs_are_sorted_capped_and_unknown_metadata_not_exposed(self):
        class Fixture:
            def get(self,path):return {'status':'success','data':{'result':[{'values':[
                ['1',json.dumps({'message':'older','secret_field':'not-exposed'})],
                ['2',json.dumps({'message':'newer','build':112})]]}]}}
        result=logs(Fixture(),parse_options('',logs=True))
        self.assertEqual(result['rows'][0]['message'],'newer')
        self.assertNotIn('not-exposed',json.dumps(result))


class GatewayHTTP(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        class FakeGrafana:
            def __init__(self,cookie):
                if 'grafana_session=synthetic-session' not in cookie:raise AuthRequired()
                self.changed={}
            def user(self):return {'login':'synthetic-viewer'}
        cls.patch=patch('server.Grafana',FakeGrafana);cls.patch.start()
        cls.httpd=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        cls.thread=threading.Thread(target=cls.httpd.serve_forever,daemon=True);cls.thread.start()
        cls.base='http://127.0.0.1:'+str(cls.httpd.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown();cls.httpd.server_close();cls.thread.join();cls.patch.stop()

    def call(self,path,cookie=None,method='GET'):
        headers={'Cookie':cookie} if cookie else {}
        request=urllib.request.Request(self.base+path,headers=headers,method=method)
        try:
            with urllib.request.urlopen(request) as response:return response.status,response.read(),response.headers
        except urllib.error.HTTPError as error:return error.code,error.read(),error.headers

    def test_anonymous_cannot_read_live_data(self):
        status,body,_=self.call('/monitoring/status-api/session')
        self.assertEqual(status,401);self.assertEqual(json.loads(body)['error'],'login_required')

    def test_existing_grafana_cookie_authenticates_without_new_credentials(self):
        status,body,headers=self.call('/monitoring/status-api/session','grafana_session=synthetic-session')
        self.assertEqual(status,200);self.assertEqual(json.loads(body)['user']['login'],'synthetic-viewer')
        self.assertEqual(headers['Cache-Control'],'no-store')

    def test_writes_and_arbitrary_proxy_routes_are_unavailable(self):
        self.assertEqual(self.call('/monitoring/status-api/overview',method='POST')[0],405)
        self.assertEqual(self.call('/monitoring/status-api/admin/settings','grafana_session=synthetic-session')[0],404)

    def test_public_shell_contains_no_live_data_and_disallows_traversal(self):
        status,body,headers=self.call('/status/')
        self.assertEqual(status,200);self.assertIn('운영 모니터링'.encode(),body)
        self.assertIn("frame-ancestors 'none'",headers['Content-Security-Policy'])
        self.assertEqual(self.call('/status/../server.py')[0],404)


if __name__=='__main__':unittest.main()
