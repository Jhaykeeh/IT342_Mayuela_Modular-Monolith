import { useState, useEffect } from 'react'
import './App.css'

function App() {
  const [selectedProduct, setSelectedProduct] = useState('')
  const [quantity, setQuantity] = useState(1)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [history, setHistory] = useState([])
  const [inventory, setInventory] = useState([])

  const currentProduct = inventory.find(p => p.productId === selectedProduct)

  useEffect(() => {
    fetch('http://localhost:8080/api/inventory')
      .then(res => {
        if (res.ok) return res.json()
        throw new Error('Could not load inventory')
      })
      .then(data => {
        if (Array.isArray(data) && data.length > 0) {
          setInventory(data)
        }
      })
      .catch(() => {})
  }, [])

  const handleSubmit = async (e) => {
    e.preventDefault()
    if (!selectedProduct) return

    setLoading(true)
    setResult(null)
    setError(null)

    try {
      const res = await fetch('http://localhost:8080/api/orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ productId: selectedProduct, quantity }),
      })

      if (!res.ok) {
        throw new Error(`Server error: ${res.status}`)
      }

      const data = await res.json()
      setResult(data)

      setHistory(prev => [
        {
          productId: selectedProduct,
          productName: currentProduct?.name || selectedProduct,
          quantity,
          status: data.status,
          reason: data.reason,
        },
        ...prev,
      ])
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <header className="app-header">
        <h1>Orders<span>/</span>Shop</h1>
      </header>

      <main className="app-main">
        <section className="form-panel">
          <h2>New Order</h2>
          <form onSubmit={handleSubmit}>
            <div className="form-group">
              <label htmlFor="product">Product</label>
              <select
                id="product"
                value={selectedProduct}
                onChange={e => setSelectedProduct(e.target.value)}
                required
              >
                <option value="" disabled>Select a product</option>
                {inventory.map(p => (
                  <option key={p.productId} value={p.productId}>
                    {p.name} ({p.productId})
                  </option>
                ))}
              </select>
              {currentProduct && (
                <p className="stock-hint">
                  {currentProduct.stock > 0
                    ? `${currentProduct.stock} in stock`
                    : 'Out of stock'}
                </p>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="quantity">Quantity</label>
              <input
                id="quantity"
                type="number"
                min="1"
                value={quantity}
                onChange={e => setQuantity(Math.max(1, parseInt(e.target.value) || 1))}
                required
              />
            </div>

            <button type="submit" className="btn-submit" disabled={loading || !selectedProduct}>
              {loading ? 'Placing order...' : 'Place order'}
            </button>

            {error && <p className="error-text">{error}</p>}
          </form>
        </section>

        <section className="result-panel">
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
              {result.inventory && (
                <div className="result-row">
                  <span className="result-label">Current stock ({result.inventory.productId})</span>
                  <span className="result-value">{result.inventory.stock}</span>
                </div>
              )}
            </div>
          ) : (
            <div className="result-card empty">
              No order placed yet.
            </div>
          )}

          <div className="history-section">
            <h2>Recent Orders</h2>
            {history.length > 0 ? (
              <ul className="history-list">
                {history.map((h, i) => (
                  <li key={i} className="history-item">
                    <span>
                      <span className="hi-product">{h.productName}</span>
                      <span className="hi-qty">x{h.quantity}</span>
                    </span>
                    <span className={`hi-status ${h.status === 'CONFIRMED' ? 'confirmed' : 'rejected'}`}>
                      {h.status}
                    </span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="history-empty">None yet.</p>
            )}
          </div>
        </section>
      </main>
    </>
  )
}

export default App
