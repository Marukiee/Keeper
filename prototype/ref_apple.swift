// Referentie: Apple's eigen HDR-naar-SDR tone map (CIToneMapHeadroom), plus een HDR-dump om de
// eigen reconstructie mee te controleren. Gebruik:
//   swiftc -O ref_apple.swift -o ref_apple
//   ./ref_apple tonemap in.jpg out.jpg [bronheadroom]   (SDR, Display P3)
//   ./ref_apple hdrdump in.jpg out.f16      (lineair extended Display P3, RGBA half-float)
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import ImageIO

let args = CommandLine.arguments
guard args.count >= 4 else { fatalError("gebruik: ref_apple tonemap|hdrdump in uit [bronheadroom]") }
let mode = args[1]
let url = URL(fileURLWithPath: args[2])
let out = URL(fileURLWithPath: args[3])

guard let img = CIImage(contentsOf: url, options: [.expandToHDR: true]) else { fatalError("laden mislukt") }
let lin = CGColorSpace(name: CGColorSpace.extendedLinearDisplayP3)!
let p3 = CGColorSpace(name: CGColorSpace.displayP3)!
let ctx = CIContext(options: [.workingColorSpace: lin])
FileHandle.standardError.write("contentHeadroom=\(img.contentHeadroom) extent=\(img.extent)\n".data(using: .utf8)!)

switch mode {
case "tonemap":
    let f = CIFilter.toneMapHeadroom()
    f.inputImage = img
    // Standaard de headroom uit de metadata; optioneel de echte piek van de inhoud
    f.sourceHeadroom = args.count > 4 ? Float(args[4])! : Float(img.contentHeadroom)
    f.targetHeadroom = 1.0
    try ctx.writeJPEGRepresentation(
        of: f.outputImage!, to: out, colorSpace: p3,
        options: [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.95])
case "hdrdump":
    let w = Int(img.extent.width), h = Int(img.extent.height)
    var buf = [UInt8](repeating: 0, count: w * h * 8)
    ctx.render(img, toBitmap: &buf, rowBytes: w * 8, bounds: img.extent, format: .RGBAh, colorSpace: lin)
    try Data(buf).write(to: out)
default:
    fatalError("onbekende modus")
}
