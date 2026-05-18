import React, { useState, useEffect } from 'react';
import axios from 'axios';

const API = '/api';

function App() {
  const [orderId, setOrderId]   = useState('');
  const [amount, setAmount]     = useState('');
  const [orders, setOrders]     = useState([]);
  const [message, setMessage]   = useState(null);
  const [loading, setLoading]   = useState(false);

  useEffect(() => { fetchOrders(); }, []);

  const fetchOrders = async () => {
    try {
      const res = await axios.get(`${API}/orders`);
      setOrders(res.data);
    } catch (e) {
      console.error('Could not load orders', e);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!orderId || !amount) return;
    setLoading(true);
    setMessage(null);
    try {
      const res = await axios.post(`${API}/orders`, {
        orderId,
        amount: parseFloat(amount)
      });
      const data = res.data;
      setMessage({
        type: data.status === 'APPROVED' ? 'success' : 'error',
        text: data.status === 'APPROVED'
          ? `✅ Order ${data.orderId} APPROVED!`
          : `❌ Order ${data.orderId} REJECTED — ${data.message}`
      });
      setOrderId('');
      setAmount('');
      fetchOrders();
    } catch (err) {
      setMessage({ type: 'error', text: '⚠️ Error: ' + err.message });
    } finally {
      setLoading(false);
    }
  };

  const statusColor = (s) =>
    s === 'APPROVED' ? '#22c55e' : s === 'REJECTED' ? '#ef4444' : '#f59e0b';

  return (
    <div style={styles.app}>
      <header style={styles.header}>
        <h1 style={styles.title}>🍽️ Gourmet-Go</h1>
        <p style={styles.subtitle}>Distributed Order System — Saga Pattern</p>
      </header>

      <main style={styles.main}>

        {/* ── Place Order ── */}
        <div style={styles.card}>
          <h2 style={styles.cardTitle}>Place an Order</h2>
          <p style={styles.hint}>
            💡 Amount &lt; 100 → <b>APPROVED</b> &nbsp;|&nbsp;
               Amount ≥ 100 → <b>REJECTED</b> (compensation triggered)
          </p>

          <form onSubmit={handleSubmit} style={styles.form}>
            <div style={styles.field}>
              <label style={styles.label}>Order ID</label>
              <input
                style={styles.input}
                type="text"
                placeholder="e.g. ORDER-001"
                value={orderId}
                onChange={e => setOrderId(e.target.value)}
                required
              />
            </div>
            <div style={styles.field}>
              <label style={styles.label}>Amount (€)</label>
              <input
                style={styles.input}
                type="number"
                placeholder="e.g. 45"
                value={amount}
                onChange={e => setAmount(e.target.value)}
                min="0"
                step="0.01"
                required
              />
            </div>
            <button
              type="submit"
              style={{
                ...styles.btn,
                opacity: loading ? 0.6 : 1
              }}
              disabled={loading}
            >
              {loading ? 'Processing Saga...' : 'Place Order'}
            </button>
          </form>

          {message && (
            <div style={{
              ...styles.message,
              background: message.type === 'success' ? '#dcfce7' : '#fee2e2',
              color:      message.type === 'success' ? '#15803d' : '#b91c1c',
            }}>
              {message.text}
            </div>
          )}
        </div>

        {/* ── Orders Table ── */}
        <div style={styles.card}>
          <div style={styles.tableHeader}>
            <h2 style={styles.cardTitle}>All Orders</h2>
            <button onClick={fetchOrders} style={styles.refreshBtn}>
              ↻ Refresh
            </button>
          </div>

          {orders.length === 0 ? (
            <p style={styles.empty}>No orders yet. Place your first order!</p>
          ) : (
            <table style={styles.table}>
              <thead>
                <tr>
                  <th style={styles.th}>Order ID</th>
                  <th style={styles.th}>Status</th>
                </tr>
              </thead>
              <tbody>
                {orders.map(o => (
                  <tr key={o.orderId}>
                    <td style={styles.td}>{o.orderId}</td>
                    <td style={styles.td}>
                      <span style={{
                        ...styles.badge,
                        background: statusColor(o.status)
                      }}>
                        {o.status}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

      </main>
    </div>
  );
}

const styles = {
  app: { minHeight: '100vh', background: '#f8fafc', fontFamily: 'system-ui, sans-serif' },
  header: { background: 'linear-gradient(135deg, #1e3a5f, #2563eb)', color: 'white', padding: '24px 40px', textAlign: 'center' },
  title: { margin: 0, fontSize: '2rem' },
  subtitle: { margin: '4px 0 0', opacity: 0.8 },
  main: { maxWidth: '700px', margin: '32px auto', padding: '0 20px', display: 'flex', flexDirection: 'column', gap: '24px' },
  card: { background: 'white', borderRadius: '12px', padding: '28px', boxShadow: '0 1px 3px rgba(0,0,0,0.07)' },
  cardTitle: { margin: '0 0 16px', fontSize: '1.1rem', color: '#1e293b' },
  hint: { background: '#f1f5f9', padding: '10px 14px', borderRadius: '6px', fontSize: '0.85rem', color: '#475569', marginBottom: '16px' },
  form: { display: 'flex', flexDirection: 'column', gap: '14px' },
  field: { display: 'flex', flexDirection: 'column', gap: '6px' },
  label: { fontSize: '0.875rem', fontWeight: '500', color: '#475569' },
  input: { padding: '10px 14px', border: '1.5px solid #e2e8f0', borderRadius: '8px', fontSize: '0.95rem' },
  btn: { padding: '11px', background: '#2563eb', color: 'white', border: 'none', borderRadius: '8px', fontSize: '0.95rem', fontWeight: '500', cursor: 'pointer' },
  message: { marginTop: '16px', padding: '12px 16px', borderRadius: '8px', fontWeight: '500' },
  tableHeader: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' },
  refreshBtn: { padding: '6px 14px', background: '#f1f5f9', border: '1px solid #e2e8f0', borderRadius: '6px', cursor: 'pointer' },
  table: { width: '100%', borderCollapse: 'collapse' },
  th: { textAlign: 'left', padding: '10px 14px', borderBottom: '2px solid #f1f5f9', color: '#64748b', fontSize: '0.82rem', textTransform: 'uppercase' },
  td: { padding: '12px 14px', borderBottom: '1px solid #f8fafc' },
  badge: { display: 'inline-block', padding: '3px 10px', borderRadius: '20px', color: 'white', fontSize: '0.78rem', fontWeight: '600' },
  empty: { color: '#94a3b8', textAlign: 'center', padding: '24px 0' },
};

export default App;
