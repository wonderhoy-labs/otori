//! Fits damped springs to the island motion tracked from the real iOS island.

use std::f64::consts::PI;

use crate::data::{Row, load, rms};

/// Closed-form damped spring from x0 to x1, parameterised like SwiftUI's spring(response:dampingFraction:).
pub fn spring(t: f64, x0: f64, x1: f64, response: f64, damping: f64, v0: f64) -> f64 {
	let w0 = 2.0 * PI / response;
	let t = t.max(0.0);
	let d = x0 - x1;
	if damping < 1.0 {
		let wd = w0 * (1.0 - damping * damping).sqrt();
		let a = damping * w0;
		x1 + (-a * t).exp() * (d * (wd * t).cos() + ((v0 + a * d) / wd) * (wd * t).sin())
	} else {
		x1 + (-w0 * t).exp() * (d + (v0 + w0 * d) * t)
	}
}

/// Bounded least squares with scipy's soft_l1 loss: the same cost as scipy.optimize.least_squares(loss="soft_l1",
/// f_scale=...), minimised with Levenberg–Marquardt on reweighted residuals and kept inside the bounds.
pub fn least_squares(
	residuals: &dyn Fn(&[f64]) -> Vec<f64>,
	guess: &[f64],
	lower: &[f64],
	upper: &[f64],
	f_scale: f64,
) -> Vec<f64> {
	let clamp = |x: &mut Vec<f64>| {
		for (i, value) in x.iter_mut().enumerate() {
			*value = value.clamp(lower[i], upper[i]);
		}
	};
	let cost = |f: &[f64]| -> f64 {
		f.iter()
			.map(|r| {
				let z = (r / f_scale).powi(2);
				f_scale * f_scale * ((1.0 + z).sqrt() - 1.0)
			})
			.sum()
	};
	let mut x = guess.to_vec();
	clamp(&mut x);
	let m = x.len();
	let mut f = residuals(&x);
	let mut current = cost(&f);
	let mut lambda = 1e-3;
	for _ in 0..2000 {
		// soft_l1's weight for each residual, so that the weighted squares have the robust cost's gradient.
		let weights: Vec<f64> = f.iter().map(|r| 1.0 / (1.0 + (r / f_scale).powi(2)).sqrt()).collect();
		let mut jacobian = vec![vec![0.0; m]; f.len()];
		for j in 0..m {
			let mut step = 1e-7 * x[j].abs().max(1.0);
			if x[j] + step > upper[j] {
				step = -step;
			}
			let mut moved = x.clone();
			moved[j] += step;
			let g = residuals(&moved);
			for (i, row) in jacobian.iter_mut().enumerate() {
				row[j] = (g[i] - f[i]) / step;
			}
		}
		let mut normal = vec![vec![0.0; m]; m];
		let mut gradient = vec![0.0; m];
		for (i, row) in jacobian.iter().enumerate() {
			for a in 0..m {
				gradient[a] += weights[i] * row[a] * f[i];
				for b in 0..m {
					normal[a][b] += weights[i] * row[a] * row[b];
				}
			}
		}
		let mut improved = false;
		while lambda < 1e12 {
			let mut damped = normal.clone();
			for (a, row) in damped.iter_mut().enumerate() {
				row[a] += lambda * normal[a][a].max(1e-12);
			}
			let Some(step) = solve(damped, gradient.iter().map(|g| -g).collect()) else {
				lambda *= 4.0;
				continue;
			};
			let mut candidate: Vec<f64> = x.iter().zip(&step).map(|(a, b)| a + b).collect();
			clamp(&mut candidate);
			let g = residuals(&candidate);
			let next = cost(&g);
			if next < current {
				let change = (current - next) / current.max(1e-300);
				x = candidate;
				f = g;
				current = next;
				lambda = (lambda / 3.0).max(1e-12);
				improved = change > 1e-12;
				break;
			}
			lambda *= 4.0;
		}
		if !improved {
			break;
		}
	}
	x
}

/// Gaussian elimination with partial pivoting, for the few parameters these fits have.
fn solve(mut a: Vec<Vec<f64>>, mut b: Vec<f64>) -> Option<Vec<f64>> {
	let n = b.len();
	for column in 0..n {
		let pivot = (column..n).max_by(|&p, &q| a[p][column].abs().total_cmp(&a[q][column].abs()))?;
		if a[pivot][column].abs() < 1e-300 {
			return None;
		}
		a.swap(column, pivot);
		b.swap(column, pivot);
		let pivot_row = a[column].clone();
		for row in column + 1..n {
			let factor = a[row][column] / pivot_row[column];
			for (value, pivot) in a[row][column..].iter_mut().zip(&pivot_row[column..]) {
				*value -= factor * pivot;
			}
			b[row] -= factor * b[column];
		}
	}
	let mut x = vec![0.0; n];
	for row in (0..n).rev() {
		let sum: f64 = (row + 1..n).map(|k| a[row][k] * x[k]).sum();
		x[row] = (b[row] - sum) / a[row][row];
	}
	Some(x)
}

fn window(rows: &[Row], from: f64, to: f64) -> Vec<Row> {
	rows.iter().filter(|r| from <= r.t && r.t <= to).cloned().collect()
}

fn fit_one(name: &str, t: &[f64], y: &[f64], x0: f64, x1: f64, guess: [f64; 3]) -> Vec<f64> {
	let residuals = |p: &[f64]| -> Vec<f64> {
		t.iter().zip(y).map(|(&tt, &yy)| spring(tt - p[2], x0, x1, p[0], p[1], 0.0) - yy).collect()
	};
	let p = least_squares(&residuals, &guess, &[0.1, 0.1, -0.1], &[2.0, 1.5, 0.3], 2.0);
	println!(
		"{name:26} response={:.3}s damping={:.3} delay={:4.0}ms rms={:.2}pt  ({x0:.1}->{x1:.1})",
		p[0],
		p[1],
		p[2] * 1000.0,
		rms(&residuals(&p))
	);
	p
}

fn collapse_width(p: &[f64], t: f64) -> f64 {
	let (r1, d1, delay, target1, switch, r2, d2) = (p[0], p[1], p[2], p[3], p[4], p[5], p[6]);
	// Phase one shrinks towards target1; phase two retargets to the compact width at `switch`, inheriting
	// position and velocity.
	if t < switch {
		return spring(t - delay, 370.5, target1, r1, d1, 0.0);
	}
	let ts = (switch - delay).max(0.0);
	let xs = spring(ts, 370.5, target1, r1, d1, 0.0);
	let eps = 1e-3;
	let vs = (spring(ts + eps, 370.5, target1, r1, d1, 0.0) - xs) / eps;
	spring(t - switch, xs, 187.1, r2, d2, vs)
}

pub fn run(args: &[String]) -> Result<(), String> {
	let [path] = args else {
		return Err("usage: otori-measure fit ios_full.csv".into());
	};
	let rows = load(path)?;

	println!("== expand (compact -> expanded)");
	let s = window(&rows, 19.50, 20.60);
	let t: Vec<f64> = s.iter().map(|r| r.t - 19.50).collect();
	let w: Vec<f64> = s.iter().map(|r| r.w_pt).collect();
	let h: Vec<f64> = s.iter().map(|r| r.h_pt).collect();
	fit_one("width", &t, &w, 187.1, 370.5, [0.55, 0.8, 0.0]);
	// Height readings glitch when the walk meets bright content mid-morph; soft_l1 keeps them from dominating.
	fit_one("height", &t, &h, 36.9, 158.4, [0.55, 0.8, 0.0]);

	println!("== collapse (expanded -> compact)");
	let s = window(&rows, 25.50, 26.60);
	let t: Vec<f64> = s.iter().map(|r| r.t - 25.50).collect();
	let w: Vec<f64> = s.iter().map(|r| r.w_pt).collect();
	let h: Vec<f64> = s.iter().map(|r| r.h_pt).collect();
	for (i, r) in s.iter().enumerate().step_by(4) {
		println!("   {:5.3} w={:6.1} h={:6.1} top={:5.2}", t[i], r.w_pt, r.h_pt, r.top_pt);
	}
	let residuals = |p: &[f64]| -> Vec<f64> { t.iter().zip(&w).map(|(&tt, &ww)| collapse_width(p, tt) - ww).collect() };
	let p = least_squares(
		&residuals,
		&[0.45, 1.0, 0.0, 126.0, 0.33, 0.45, 0.7],
		&[0.1, 0.3, -0.1, 80.0, 0.1, 0.1, 0.3],
		&[1.2, 1.5, 0.2, 190.0, 0.7, 1.2, 1.5],
		2.0,
	);
	println!(
		"width two-phase: response1={:.3} damping1={:.3} delay={:.0}ms target1={:.1} switch={:.3}s response2={:.3} damping2={:.3} rms={:.2}pt",
		p[0],
		p[1],
		p[2] * 1000.0,
		p[3],
		p[4],
		p[5],
		p[6],
		rms(&residuals(&p))
	);
	fit_one("height", &t, &h, 158.4, 36.9, [0.45, 1.0, 0.0]);
	fit_one("width single spring", &t, &w, 370.5, 187.1, [0.6, 0.45, 0.0]);
	Ok(())
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn a_spring_starts_at_x0_and_settles_at_x1() {
		assert!((spring(0.0, 10.0, 20.0, 0.5, 0.8, 0.0) - 10.0).abs() < 1e-9);
		assert!((spring(10.0, 10.0, 20.0, 0.5, 0.8, 0.0) - 20.0).abs() < 1e-6);
		assert!((spring(10.0, 10.0, 20.0, 0.5, 1.0, 0.0) - 20.0).abs() < 1e-6);
	}

	// From a damping of exactly 1.0, the critically damped formula doesn't depend on damping, so the fit can't leave
	// it. scipy gets stuck at the same point, (0.40822, 1.0, 0.05845), and so does this.
	#[test]
	fn least_squares_stops_where_scipy_does_at_critical_damping() {
		let t: Vec<f64> = (0..120).map(|i| i as f64 / 100.0).collect();
		let y: Vec<f64> = t.iter().map(|&tt| spring(tt - 0.04, 187.1, 370.5, 0.55, 0.8, 0.0)).collect();
		let residuals = |p: &[f64]| -> Vec<f64> {
			t.iter().zip(&y).map(|(&tt, &yy)| spring(tt - p[2], 187.1, 370.5, p[0], p[1], 0.0) - yy).collect()
		};
		let p = least_squares(&residuals, &[0.4, 1.0, 0.0], &[0.1, 0.1, -0.1], &[2.0, 1.5, 0.3], 2.0);
		assert!((p[0] - 0.40822).abs() < 1e-4 && p[1] == 1.0 && (p[2] - 0.05845).abs() < 1e-4, "{p:?}");
	}

	#[test]
	fn least_squares_finds_a_known_spring() {
		let t: Vec<f64> = (0..120).map(|i| i as f64 / 100.0).collect();
		let y: Vec<f64> = t.iter().map(|&tt| spring(tt - 0.04, 187.1, 370.5, 0.55, 0.8, 0.0)).collect();
		let residuals = |p: &[f64]| -> Vec<f64> {
			t.iter().zip(&y).map(|(&tt, &yy)| spring(tt - p[2], 187.1, 370.5, p[0], p[1], 0.0) - yy).collect()
		};
		let p = least_squares(&residuals, &[0.4, 0.9, 0.0], &[0.1, 0.1, -0.1], &[2.0, 1.5, 0.3], 2.0);
		assert!((p[0] - 0.55).abs() < 1e-4, "{p:?}");
		assert!((p[1] - 0.8).abs() < 1e-4, "{p:?}");
		assert!((p[2] - 0.04).abs() < 1e-4, "{p:?}");
	}
}
