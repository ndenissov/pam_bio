import os
import glob
from PIL import Image

src_dir = "screenshots"
out_dir = os.path.join(src_dir, "aptoide")

if not os.path.exists(out_dir):
    os.makedirs(out_dir)

for file in glob.glob(os.path.join(src_dir, "*.*")):
    if not file.lower().endswith(('.png', '.jpg', '.jpeg')):
        continue
    
    if os.path.isdir(file):
        continue

    filename = os.path.basename(file)
    try:
        img = Image.open(file).convert('RGB')
        w, h = img.size
        
        # Aptoide requires H/W < 2. We'll target 16:9 ratio (H/W ≈ 1.77)
        if h / w >= 2.0:
            target_w = int(h * 9 / 16)
            
            # Sample top-left pixel for background color
            bg_color = img.getpixel((0, 0))
            
            new_img = Image.new('RGB', (target_w, h), bg_color)
            
            # Paste original image in the center
            offset = ((target_w - w) // 2, 0)
            new_img.paste(img, offset)
            
            out_path = os.path.join(out_dir, filename)
            new_img.save(out_path, quality=95)
            print(f"Padded {filename}: {w}x{h} -> {target_w}x{h}")
        else:
            print(f"Skipped {filename}, ratio is {h/w:.2f} (already < 2)")
            
    except Exception as e:
        print(f"Error processing {filename}: {e}")

print("Done!")
