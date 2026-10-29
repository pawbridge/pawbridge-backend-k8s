"""Image recommendations reuse published SAM3/DINOv3 features without inference."""
from app.vision.coat_color import VERSION as COLOR_VERSION, ranking_weight
from app.vision.dinov3 import gallery_index
from app.lost_search.service import rank_candidates
from app.gallery.storage import get_postgresql_store
from app.vision.sam3_focus import FOCUS_VERSION

MAX_RECOMMENDATIONS = 6
# Compatibility floor; not an identity probability or a calibrated DINOv3 threshold.
MIN_VISUAL_SCORE = 0.6


def recommend_animals(animal_id, species):
    weight = ranking_weight()
    source, hits = get_postgresql_store().recommendation_candidates(
        gallery_index(), animal_id, species, FOCUS_VERSION, COLOR_VERSION if weight else None)
    # Connection returned before color reranking. No remote image, GPU or full-gallery cache.
    eligible = [hit for hit in hits if float(hit['_score']) - 1.0 >= MIN_VISUAL_SCORE]
    ranked = rank_candidates(eligible, coat_color=source.get('coat_color'), color_weight=weight)
    return [candidate['animalId'] for candidate in ranked][:MAX_RECOMMENDATIONS]
