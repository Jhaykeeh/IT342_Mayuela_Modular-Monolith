import { useState, useEffect, useCallback } from 'react'
import './App.css'

const API = 'http://localhost:8080'
const LOW_STOCK = 5

function App() {
  const [cart, setCart] = useState([])
  const [selectedProduct, setSelectedProduct] = useState('')
  const [inventory, setInventory] = useState([])
  const [orders, setOrders] = useState([])
  const [notifications, setNotifications] = useState([])
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const refreshAll = useCallback(async () => {
    const [inv, ord, notif] = await Promise.all([
      fetch(`${API}/api/inventory`).then(r => r.json()),
      fetch(`${API}/api/orders`).then(r => r.json()),
      fetch(`${API}/api/notifications`).then(r => r.json()),
    ])
    if (Array.isArray(inv)) setInventory(inv)
    if (Array.isArray(ord)) setOrders(ord)
    if (Array.isArray(notif)) setNotifications(notif)
  }, [])

  useEffect(() => {
    refreshAll().catch(() => {})
  }, [refreshAll])

  const addToCart = () => {
    if (!selectedProduct) return
    const existing = cart.find(c => c.productId === selectedProduct)
    if (existing) {
      setCart(cart.map(c => c.productId === selectedProduct
        ? { ...c, quantity: c.quantity + 1 }
        : c))
    } else {
      setCart([...cart, { productId: selectedProduct, quantity: 1 }])
    }
  }

  const updateQty = (productId, quantity) => {
    const qty = Math.max(1, parseInt(quantity) || 1)
    setCart(cart.map(c => c.productId === productId ? { ...c, quantity: qty } : c))
  }

  const removeFromCart = (productId) => {
    setCart(cart.filter(c => c.productId !== productId))
  }

  const productName = (productId) =>
    inventory.find(p => p.productId === productId)?.name || productId

  const submitOrder = async (e) => {
    e.preventDefault()
    if (cart.length === 0) return

    setLoading(true)
    setResult(null)
    setError(null)

    try {
      const res = await fetch(`${API}/api/orders`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ items: cart }),
      })
      if (!res.ok) throw new Error(`Server error: ${res.status}`)
      const data = await res.json()
      setResult(data)
      setCart([])
      if (data.status === 'CONFIRMED') setSelectedProduct('')
      await refreshAll()
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  const cancelOrder = async (orderId) => {
    try {
      const res = await fetch(`${API}/api/orders/${orderId}/cancel`, { method: 'POST' })
      if (res.status === 409) {
        alert('This order is already cancelled.')
        return
      }
      if (!res.ok) throw new Error(`Server error: ${res.status}`)
      await refreshAll()
    } catch (err) {
      alert(err.message)
    }
  }

  const statusClass = (status) => {
    if (status === 'CONFIRMED') return 'status-confirmed'
    if (status === 'REJECTED') return 'status-rejected'
    return 'status-cancelled'
  }

  return (
    <>
      <header className="app-header">
        <h1>Orders<span>/</span>Shop</h1>
      </header>

      <main className="app-main">
        <section className="col-left">
          <form onSubmit={submitOrder}>
            <div className="panel">
              <h2>New Order</h2>

              <div className="form-group">
                <label htmlFor="product">Add product to cart</label>
                <div className="add-row">
                  <select
                    id="product"
                    value={selectedProduct}
                    onChange={e => setSelectedProduct(e.target.value)}
                  >
                    <option value="" disabled>Select a product</option>
                    {inventory.map(p => (
                      <option key={p.productId} value={p.productId}>
                        {p.name} ({p.productId}) — {p.stock} in stock
                      </option>
                    ))}
                  </select>
                  <button
                    type="button"
                    className="btn-add"
                    onClick={addToCart}
                    disabled={!selectedProduct}
                  >
                    Add
                  </button>
                </div>
              </div>

              {cart.length > 0 ? (
                <ul className="cart-list">
                  {cart.map(c => (
                    <li key={c.productId} className="cart-item">
                      <span className="cart-name">{productName(c.productId)}</span>
                      <input
                        type="number"
                        min="1"
                        value={c.quantity}
                        onChange={e => updateQty(c.productId, e.target.value)}
                      />
                      <button
                        type="button"
                        className="btn-remove"
                        onClick={() => removeFromCart(c.productId)}
                      >
                        Remove
                      </button>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="muted">Cart is empty. Add one or more products above.</p>
              )}

              <button
                type="submit"
                className="btn-submit"
                disabled={loading || cart.length === 0}
              >
                {loading ? 'Placing order...' : 'Place order'}
              </button>

              {error && <p className="error-text">{error}</p>}
            </div>
          </form>

          <div className="panel">
            <h2>Activity Feed</h2>
            {notifications.length > 0 ? (
              <ul className="notif-list">
                {notifications.map(n => (
                  <li
                    key={n.notificationId}
                    className={`notif-item ${n.message.includes('Reorder') ? 'notif-low' : ''}`}
                  >
                    <span className="notif-msg">{n.message}</span>
                    <span className="notif-time">{formatTime(n.createdAt)}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">No notifications yet.</p>
            )}
          </div>
        </section>

        <section className="col-right">
          <div className="panel">
            <h2>Result</h2>
            {result ? (
              <div className={`result-card ${result.status === 'CONFIRMED' ? 'confirmed-card' : 'rejected-card'}`}>
                <div className="result-row">
                  <span className="result-label">Status</span>
                  <span className={`result-value ${result.status === 'CONFIRMED' ? 'confirmed' : 'rejected'}`}>
                    {result.status}
                  </span>
                </div>
                {result.reason && (
                  <div className="result-row">
                    <span className="result-label">Reason</span>
                    <span className="result-value">{result.reason}</span>
                  </div>
                )}
                {result.items?.length > 0 && (
                  <div className="result-items">
                    <div className="result-label">Items</div>
                    {result.items.map(it => (
                      <div key={it.productId} className="result-item-line">
                        <span>{productName(it.productId)} ({it.productId})</span>
                        <span className={`item-outcome ${it.outcome === 'RESERVED' ? 'confirmed' : 'rejected'}`}>
                          {it.outcome}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ) : (
              <div className="result-card empty">No order placed yet.</div>
            )}
          </div>

          <div className="panel">
            <h2>Inventory</h2>
            <table className="inv-table">
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Product</th>
                  <th>Stock</th>
                </tr>
              </thead>
              <tbody>
                {inventory.map(p => (
                  <tr key={p.productId} className={p.stock < LOW_STOCK ? 'low-stock' : ''}>
                    <td>{p.productId}</td>
                    <td>{p.name}</td>
                    <td className={p.stock < LOW_STOCK ? 'text-danger' : ''}>{p.stock}</td>
                  </tr>
                ))}
                {inventory.length === 0 && (
                  <tr><td colSpan="3" className="muted">Loading…</td></tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="panel">
            <h2>Order History</h2>
            {orders.length > 0 ? (
              <ul className="history-list">
                {orders.map(o => (
                  <li key={o.orderId} className="history-item">
                    <div className="history-main">
                      <span className="hi-order">
                        #{o.orderId}
                        <span className={`hi-status ${statusClass(o.status)}`}>{o.status}</span>
                      </span>
                      {o.reason && <span className="hi-reason">{o.reason}</span>}
                      <span className="hi-items">
                        {o.items.map(i => `${productName(i.productId)} x${i.quantity}`).join(', ')}
                      </span>
                    </div>
                    {o.status === 'CONFIRMED' && (
                      <button className="btn-cancel" onClick={() => cancelOrder(o.orderId)}>
                        Cancel
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">No orders yet.</p>
            )}
          </div>
        </section>
      </main>
    </>
  )
}

function formatTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

export default App