//! Measures the island's motion in screen recordings, and fits springs to it. This is how Otori's animation was
//! matched to the real Dynamic Island; the README's "Under the hood" walks through it.
//!
//! otori-measure track in.mp4 out.csv --crop W:H:X:Y --ppp PX_PER_POINT   tracks the island in a recording
//! otori-measure segments capture.csv                                     lists the motion in a capture
//! otori-measure fit tools/measure/ios_full.csv                            fits springs to the iOS island
//! otori-measure compare tools/measure/ios_full.csv android.csv           compares Android's motion with iOS

mod compare;
mod data;
mod fit;
mod track;

use std::process::ExitCode;

fn main() -> ExitCode {
	let args: Vec<String> = std::env::args().skip(1).collect();
	let result = match args.first().map(String::as_str) {
		Some("track") => track::run(&args[1..]),
		Some("segments") => compare::segments(&args[1..]),
		Some("fit") => fit::run(&args[1..]),
		Some("compare") => compare::compare(&args[1..]),
		_ => Err("usage: otori-measure track|segments|fit|compare ...".into()),
	};
	match result {
		Ok(()) => ExitCode::SUCCESS,
		Err(message) => {
			eprintln!("{message}");
			ExitCode::FAILURE
		}
	}
}
