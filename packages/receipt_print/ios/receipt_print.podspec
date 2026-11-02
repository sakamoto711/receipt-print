Pod::Spec.new do |s|
  s.name = 'receipt_print'
  s.version = '0.1.0'
  s.summary = 'Receipt command and preview conversion using the KMP receipt-print engine.'
  s.description = 'Converts generic print trees into printer commands and ReceiptLine previews using the KMP engine. Printer transport is owned by the caller.'
  s.homepage = 'https://github.com/sakamoto711/receipt-print'
  s.license = { :type => 'UNLICENSED' }
  s.author = 'receipt-print contributors'
  s.source = { :path => '.' }
  s.source_files = 'receipt_print/Sources/receipt_print/**/*.swift'
  s.dependency 'Flutter'
  s.platform = :ios, '15.0'
  s.vendored_frameworks = 'Frameworks/ReceiptPrint.xcframework'
  s.static_framework = true
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386 x86_64'
  }
  s.swift_version = '5.0'
end
