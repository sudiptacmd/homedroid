"""Optimize captured images for the site; preserve supplied phone originals. Needs Pillow."""
from pathlib import Path
from PIL import Image
import shutil

media = Path('site/media')
docs = Path('docs/screenshots')
for source in (media / 'new').glob('*.png'):
    image = Image.open(source).convert('RGB')
    image.thumbnail((1440, 1200))
    image.save(media / (source.stem + '.webp'), quality=86, method=6)
for source in media.glob('dashboard-*.png'):
    Image.open(source).convert('RGB').save(source.with_suffix('.webp'), quality=86, method=6)
for source, target in [('overview', 'overview-dark'), ('files', 'files-dark'),
                       ('terminal', 'terminal-dark'), ('deploys', 'deploys-dark'),
                       ('ai', 'ai-dark'), ('cluster', 'cluster-dark'),
                       ('overview-light', 'overview-light'), ('customize', 'customize'),
                       ('modules', 'modules-dark')]:
    original = media / f'dashboard-{source}.png'
    if original.exists():
        shutil.copyfile(original, docs / f'dashboard-{target}.png')
for source, target in [('overview-light', 'main'), ('apps-light', 'apps'),
                       ('settings-oled', 'settings'), ('macropads-oled', 'macropads'), ('dock', 'dock')]:
    image = Image.open(media / 'new' / f'phone-{source}.png')
    image.thumbnail((1440, 1200))
    image.save(docs / f'phone-{target}.png', optimize=True)
for source in media.glob('dashboard-*.png'):
    source.unlink()
print('WebP site images and README PNGs updated; capture intermediates removed.')
