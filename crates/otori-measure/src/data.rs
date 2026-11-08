//! Reading tracked captures, and the bits of numpy the measurements lean on.

use std::collections::HashMap;
use std::fs;

/// One row of a tracked capture, as `track` writes it: frame, t, w_pt, h_pt, top_pt, cx_pt, radius_pt.
#[derive(Clone, Debug, Default)]
pub struct Row {
	pub t: f64,
	pub w_pt: f64,
	pub h_pt: f64,
	pub top_pt: f64,
	pub radius_pt: f64,
}

/// Reads a tracked capture. Columns are found by name, like Python's DictReader.
pub fn load(path: &str) -> Result<Vec<Row>, String> {
	let text = fs::read_to_string(path).map_err(|error| format!("{path}: {error}"))?;
	let mut lines = text.lines();
	let header: Vec<&str> = lines.next().ok_or_else(|| format!("{path} is empty"))?.split(',').collect();
	let column: HashMap<&str, usize> = header.iter().enumerate().map(|(i, name)| (name.trim(), i)).collect();
	let index = |name: &str| column.get(name).copied().ok_or_else(|| format!("{path} has no {name} column"));
	let (t, w, h, top) = (index("t")?, index("w_pt")?, index("h_pt")?, index("top_pt")?);
	let radius = column.get("radius_pt").copied();
	let mut rows = Vec::new();
	for (number, line) in lines.enumerate() {
		if line.trim().is_empty() {
			continue;
		}
		let cells: Vec<&str> = line.split(',').collect();
		let value = |i: usize| -> Result<f64, String> {
			cells
				.get(i)
				.and_then(|cell| cell.trim().parse().ok())
				.ok_or_else(|| format!("{path}, line {}: column {} isn't a number", number + 2, i + 1))
		};
		rows.push(Row {
			t: value(t)?,
			w_pt: value(w)?,
			h_pt: value(h)?,
			top_pt: value(top)?,
			radius_pt: radius.map(value).transpose()?.unwrap_or(0.0),
		});
	}
	Ok(rows)
}

/// numpy.interp: piecewise linear, holding the end values outside the data.
pub fn interp(x: f64, xs: &[f64], ys: &[f64]) -> f64 {
	let (Some(&first), Some(&last)) = (xs.first(), xs.last()) else {
		return f64::NAN;
	};
	if x <= first {
		return ys[0];
	}
	if x >= last {
		return ys[ys.len() - 1];
	}
	let right = xs.partition_point(|&value| value <= x);
	let left = right - 1;
	let (x0, x1, y0, y1) = (xs[left], xs[right], ys[left], ys[right]);
	if x1 == x0 { y0 } else { y0 + (y1 - y0) * (x - x0) / (x1 - x0) }
}

/// numpy.arange for floats: start + k * step while it stays below stop.
pub fn arange(start: f64, stop: f64, step: f64) -> Vec<f64> {
	let count = ((stop - start) / step).ceil().max(0.0) as usize;
	(0..count).map(|k| start + k as f64 * step).collect()
}

/// numpy.percentile with its default linear interpolation.
pub fn percentile(values: &[f64], percent: f64) -> f64 {
	let mut sorted = values.to_vec();
	sorted.sort_by(f64::total_cmp);
	if sorted.is_empty() {
		return f64::NAN;
	}
	let rank = percent / 100.0 * (sorted.len() - 1) as f64;
	let low = rank.floor() as usize;
	let high = rank.ceil() as usize;
	sorted[low] + (sorted[high] - sorted[low]) * (rank - low as f64)
}

/// numpy.median.
pub fn median(values: &[f64]) -> f64 {
	percentile(values, 50.0)
}

pub fn rms(values: &[f64]) -> f64 {
	(values.iter().map(|v| v * v).sum::<f64>() / values.len() as f64).sqrt()
}

/// Python's repr of round(value, places), which leaves trailing zeros off but keeps one after the point.
pub fn py_round(value: f64, places: i32) -> String {
	let scale = 10f64.powi(places);
	let rounded = (value * scale).round() / scale;
	if rounded.fract() == 0.0 { format!("{rounded:.1}") } else { format!("{rounded}") }
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn interp_holds_the_ends_and_joins_the_points() {
		let xs = [0.0, 1.0, 3.0];
		let ys = [10.0, 20.0, 0.0];
		assert_eq!(interp(-1.0, &xs, &ys), 10.0);
		assert_eq!(interp(0.5, &xs, &ys), 15.0);
		assert_eq!(interp(2.0, &xs, &ys), 10.0);
		assert_eq!(interp(9.0, &xs, &ys), 0.0);
	}

	#[test]
	fn arange_matches_numpy() {
		assert_eq!(arange(-0.08, 0.08, 0.002).len(), 80);
		assert_eq!(arange(0.0, 0.85, 0.012).len(), 71);
	}

	#[test]
	fn percentile_and_median_match_numpy() {
		assert_eq!(percentile(&[1.0, 2.0, 3.0, 4.0], 85.0), 3.55);
		assert_eq!(median(&[3.0, 1.0, 2.0, 10.0]), 2.5);
	}

	#[test]
	fn py_round_reads_like_python() {
		assert_eq!(py_round(0.0, 4), "0.0");
		assert_eq!(py_round(0.003_33, 4), "0.0033");
		assert_eq!(py_round(125.144, 2), "125.14");
		assert_eq!(py_round(36.0, 2), "36.0");
	}
}
