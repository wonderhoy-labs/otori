//! Lists a capture's motion segments, and compares Android's motion with the real iOS island's.

use crate::data::{Row, arange, interp, load, percentile, py_round, rms};

/// Lists motion segments in a tracked capture.
pub fn segments(args: &[String]) -> Result<(), String> {
	let [path] = args else {
		return Err("usage: otori-measure segments capture.csv".into());
	};
	let rows = load(path)?;
	let mut segments: Vec<[f64; 2]> = Vec::new();
	for pair in rows.windows(2) {
		let (a, b) = (&pair[0], &pair[1]);
		if (a.w_pt - b.w_pt).abs() > 0.25 || (a.h_pt - b.h_pt).abs() > 0.25 {
			match segments.last_mut() {
				Some(current) if a.t - current[1] <= 0.3 => current[1] = b.t,
				_ => segments.push([a.t, b.t]),
			}
		}
	}
	let at =
		|t: f64| -> &Row { rows.iter().min_by(|p, q| (p.t - t).abs().total_cmp(&(q.t - t).abs())).unwrap_or(&rows[0]) };
	for (i, s) in segments.iter().enumerate() {
		let (r0, r1) = (at(s[0] - 0.06), at(s[1] + 0.06));
		println!(
			"{i} t={:.2}..{:.2} ({:.2}s) w {:.1}->{:.1} h {:.1}->{:.1} top {:.1}->{:.1} r {:.1}->{:.1}",
			s[0],
			s[1],
			s[1] - s[0],
			r0.w_pt,
			r1.w_pt,
			r0.h_pt,
			r1.h_pt,
			r0.top_pt,
			r1.top_pt,
			r0.radius_pt,
			r1.radius_pt
		);
	}
	Ok(())
}

struct Track {
	t: Vec<f64>,
	w: Vec<f64>,
	h: Vec<f64>,
}

impl Track {
	fn load(path: &str) -> Result<Self, String> {
		let rows = load(path)?;
		Ok(Self {
			t: rows.iter().map(|r| r.t).collect(),
			w: rows.iter().map(|r| r.w_pt).collect(),
			h: rows.iter().map(|r| r.h_pt).collect(),
		})
	}
}

fn first_crossing(t: &[f64], y: &[f64], (start, end): (f64, f64), level: f64, rising: bool) -> Option<f64> {
	t.iter()
		.zip(y)
		.filter(|&(&a, _)| start <= a && a <= end)
		.find(|&(_, &v)| if rising { v >= level } else { v <= level })
		.map(|(&a, _)| a)
}

#[allow(clippy::too_many_arguments)]
fn compare_one(
	name: &str,
	ios: &Track,
	android: &Track,
	ios_window: (f64, f64),
	android_window: (f64, f64),
	level: f64,
	rising: bool,
	span: f64,
) {
	let (Some(t0_i), Some(t0_a)) = (
		first_crossing(&ios.t, &ios.w, ios_window, level, rising),
		first_crossing(&android.t, &android.w, android_window, level, rising),
	) else {
		println!("{name}: no transition found");
		return;
	};
	// Refine the alignment; only the transition's start time is free, not its shape.
	let grid = arange(0.0, span, 0.012);
	let sample = |track: &Track, values: &[f64], start: f64| -> Vec<f64> {
		grid.iter().map(|g| interp(g + start, &track.t, values)).collect()
	};
	let mut best = (1e9, 0.0);
	for shift in arange(-0.08, 0.08, 0.002) {
		let pa = sample(android, &android.w, t0_a + shift);
		let pi = sample(ios, &ios.w, t0_i);
		let difference: Vec<f64> = pa.iter().zip(&pi).map(|(a, b)| a - b).collect();
		let error = rms(&difference);
		if error < best.0 {
			best = (error, shift);
		}
	}
	let shift = best.1;
	let pa_w = sample(android, &android.w, t0_a + shift);
	let pi_w = sample(ios, &ios.w, t0_i);
	let pa_h = sample(android, &android.h, t0_a + shift);
	let pi_h = sample(ios, &ios.h, t0_i);
	// The iOS height reading glitches where its walk meets bright content, so outliers are dropped. Heights differ in
	// their end point (the media island is taller than the Live Activity it was measured on), so the shapes are
	// compared as fractions of their own travel.
	let fraction = |h: &[f64]| -> Vec<f64> {
		let low = 37.16;
		let high = h.iter().copied().fold(f64::NEG_INFINITY, f64::max).max(38.0);
		h.iter().map(|v| (v - low) / (high - low)).collect()
	};
	let df: Vec<f64> = fraction(&pa_h).iter().zip(fraction(&pi_h)).map(|(a, b)| a - b).collect();
	let magnitudes: Vec<f64> = df.iter().map(|d| d.abs()).collect();
	let cutoff = percentile(&magnitudes, 85.0) + 1e-6;
	let good: Vec<f64> = df.iter().copied().filter(|d| d.abs() < cutoff).collect();
	println!("{name}: width RMS {:.2} pt, height shape RMS {:.1}% of travel", best.0, rms(&good) * 100.0);
	println!("   t(s)   iOS w   And w   |  iOS h   And h");
	for i in (0..grid.len()).step_by((grid.len() / 14).max(1)) {
		println!("   {:4.2}  {:6.1}  {:6.1}   | {:6.1}  {:6.1}", grid[i], pi_w[i], pa_w[i], pi_h[i], pa_h[i]);
	}
}

/// Finds each Android transition, slides it in time onto the iOS curve, and reports the remaining error.
pub fn compare(args: &[String]) -> Result<(), String> {
	let [ios_path, android_path] = args else {
		return Err("usage: otori-measure compare tools/measure/ios_full.csv android.csv".into());
	};
	let ios = Track::load(ios_path)?;
	let android = Track::load(android_path)?;
	let mut rising = Vec::new();
	let mut falling = Vec::new();
	let mut previous = android.w.first().copied().unwrap_or(0.0);
	for (&t, &w) in android.t.iter().zip(&android.w) {
		if previous < 230.0 && 230.0 <= w {
			rising.push(t);
		}
		if previous > 300.0 && 300.0 >= w {
			falling.push(t);
		}
		previous = w;
	}
	let list = |values: &[f64]| values.iter().map(|v| py_round(*v, 2)).collect::<Vec<_>>().join(", ");
	println!("android transitions: expand at [{}] collapse at [{}]", list(&rising), list(&falling));
	if let Some(&t) = rising.first() {
		compare_one("EXPAND", &ios, &android, (19.4, 20.4), (t - 0.5, t + 0.5), 230.0, true, 0.85);
	}
	if let Some(&t) = falling.first() {
		compare_one("COLLAPSE", &ios, &android, (25.4, 26.4), (t - 0.5, t + 0.5), 300.0, false, 0.64);
	}
	Ok(())
}
