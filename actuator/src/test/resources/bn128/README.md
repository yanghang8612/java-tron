# BN128 known-answer vectors

Copied unchanged from the go-ethereum test corpus at commit
`64006a1e1c6281ad570d80129493d602fe081407`:

- https://github.com/ethereum/go-ethereum/blob/64006a1e1c6281ad570d80129493d602fe081407/core/vm/testdata/precompiles/bn256Add.json
- https://github.com/ethereum/go-ethereum/blob/64006a1e1c6281ad570d80129493d602fe081407/core/vm/testdata/precompiles/bn256ScalarMul.json
- https://github.com/ethereum/go-ethereum/blob/64006a1e1c6281ad570d80129493d602fe081407/core/vm/testdata/precompiles/bn256Pairing.json

The historical java-tron PR #5611 also used the go-ethereum corpus. These vectors test returned
bytes, not TRON energy prices. Invalid input and subgroup rejection cases are covered separately.
Upstream go-ethereum library code is LGPL-3.0-or-later; retain this provenance when updating.
