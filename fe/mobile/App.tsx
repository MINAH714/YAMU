import { useEffect, useState } from 'react';
import { StatusBar } from 'expo-status-bar';
import { StyleSheet, Text, View } from 'react-native';

const apiBaseUrl = process.env.EXPO_PUBLIC_API_BASE_URL?.replace(/\/+$/, '');

export default function App() {
  const [apiStatus, setApiStatus] = useState(
    apiBaseUrl ? '확인 중' : 'API 주소를 설정해 주세요',
  );

  useEffect(() => {
    if (!apiBaseUrl) return;

    const controller = new AbortController();

    fetch(`${apiBaseUrl}/api/health`, { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error('API 응답 오류');
        const data: { status?: string } = await response.json();
        setApiStatus(data.status === 'ok' ? '연결됨' : 'API 응답 오류');
      })
      .catch((error: Error) => {
        if (error.name !== 'AbortError') setApiStatus('연결 안 됨');
      });

    return () => controller.abort();
  }, []);

  return (
    <View style={styles.container}>
      <Text style={styles.title}>YAMU</Text>
      <Text style={styles.subtitle}>결제는 간편하게, 혜택은 야무지게.</Text>
      <Text style={styles.status}>BE 연결 상태: {apiStatus}</Text>
      <StatusBar style="dark" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
    backgroundColor: '#f7f9fc',
  },
  title: {
    fontSize: 36,
    fontWeight: '700',
    color: '#16324f',
  },
  subtitle: {
    marginTop: 12,
    fontSize: 16,
    color: '#41566b',
    textAlign: 'center',
  },
  status: {
    marginTop: 32,
    fontSize: 14,
    color: '#225c84',
  },
});
