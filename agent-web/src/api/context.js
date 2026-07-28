import { get, post } from './http.js'

export function getContextCapabilities() {
  return get('/api/framework/context')
}

export function runContextDemo(payload) {
  return post('/api/context/demo', payload)
}
